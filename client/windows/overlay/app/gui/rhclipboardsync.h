#pragma once
// Two-way clipboard synchronization for one RHRemote stream (Windows client).
//
// Local clipboard changes are announced to the host as a format list; the host takes delayed ownership.
// Host clipboard changes arrive through a long poll and are mirrored locally with delayed rendering.
// Data only crosses the network when an application actually pastes, in chunks, over the paired HTTPS
// GameStream port. Transfers that outlast the pasting application's patience continue in the background
// and are placed on the clipboard when complete.

#include <QtGlobal>

#ifdef Q_OS_WIN32
#include "backend/identitymanager.h"
#include <QByteArray>
#include <QDateTime>
#include <QElapsedTimer>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMap>
#include <QMutex>
#include <QMutexLocker>
#include <QNetworkProxy>
#include <QSslSocket>
#include <QString>
#include <QStringList>
#include <QThread>
#include <QVector>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <functional>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <windows.h>

namespace RhClipboardWire {
constexpr int version = 1;
constexpr qint64 chunkSize = qint64(4) << 20;
constexpr quint64 maxTotal = quint64(4) << 30;
constexpr int maxFormats = 64;
constexpr int maxFormatName = 255;
constexpr int renderBudgetMs = 10000;  // How long a pasting application may wait before the transfer moves to the background.
constexpr int idleMs = 15000;          // A transfer without progress for this long fails.
constexpr int pollWaitMs = 25000;

struct Format {
    quint32 id = 0;
    QString name;  // Empty for standard formats.
    bool operator==(const Format& other) const { return id == other.id && name == other.name; }
};

// Same policy as the host (server/sunshine/src/rhremote_clipboard.h); keep both lists identical.
inline bool standardTransferable(quint32 id) {
    switch (id) {
    case 1: case 4: case 5: case 6: case 7: case 8: case 11: case 12: case 13: case 16: case 17:
        return true;
    default:
        return false;
    }
}

inline bool registeredBlocked(const QString& name) {
    static const QStringList blocked{
        "DataObject", "Ole Private Data", "Object Descriptor", "Link Source Descriptor", "Embed Source", "Link Source",
        "Embedded Object", "OwnerLink", "ObjectLink", "Native", "Shell IDList Array", "Shell Object Offsets", "FileName",
        "FileNameW", "FileNameMap", "FileNameMapW", "FileGroupDescriptor", "FileGroupDescriptorW", "FileContents",
        "Preferred DropEffect", "Performed DropEffect", "Logical Performed DropEffect", "Paste Succeeded", "InShellDragLoop",
        "TargetCLSID", "DragContext", "DragImageBits", "DragSourceHelperFlags", "DropDescription", "UsingDefaultDragImage",
        "IsShowingLayered", "IsShowingText", "IsComputingImage", "ComputedDragImage", "DisableDragText", "UntrustedDragDrop"};
    return name.isEmpty() || name.toUtf8().size() > maxFormatName || blocked.contains(name);
}

inline bool transferable(const Format& format) {
    return format.id >= 0xC000 ? !registeredBlocked(format.name) : format.name.isEmpty() && standardTransferable(format.id);
}

inline QVector<Format> select(const QVector<Format>& available) {
    const auto has = [&](quint32 id) {
        for (const auto& f : available) if (f.id == id && f.name.isEmpty()) return true;
        return false;
    };
    const bool unicode = has(13), dibv5 = has(17);
    QVector<Format> selected;
    for (const auto& format : available) {
        if (selected.size() >= maxFormats) break;
        if (!transferable(format)) continue;
        if (format.id < 0xC000 && ((unicode && (format.id == 1 || format.id == 7)) || (dibv5 && format.id == 8))) continue;
        if (!selected.contains(format)) selected.push_back(format);
    }
    return selected;
}

inline QJsonArray toJson(const QVector<Format>& formats) {
    QJsonArray array;
    for (const auto& f : formats) {
        QJsonObject entry{{"id", double(f.id)}};
        if (!f.name.isEmpty()) entry["name"] = f.name;
        array.append(entry);
    }
    return array;
}

inline std::optional<QVector<Format>> parse(const QJsonValue& value) {
    if (!value.isArray() || value.toArray().size() > maxFormats) return std::nullopt;
    QVector<Format> formats;
    for (const auto& item : value.toArray()) {
        const auto entry = item.toObject();
        const double id = entry.value("id").toDouble(-1);
        if (!item.isObject() || id < 1 || id > 0xFFFF || id != double(quint32(id))) return std::nullopt;
        Format format{quint32(id), entry.value("name").toString()};
        if (entry.contains("name") && !entry.value("name").isString()) return std::nullopt;
        if (!transferable(format)) return std::nullopt;
        formats.push_back(format);
    }
    return formats;
}

inline quint64 memoryBudget() {
    MEMORYSTATUSEX status{sizeof(status)};
    if (!GlobalMemoryStatusEx(&status)) return quint64(256) << 20;
    return qMin<quint64>(status.ullAvailPhys / 2, maxTotal);
}

inline HGLOBAL toGlobal(const QByteArray& data) {
    const auto memory = GlobalAlloc(GMEM_MOVEABLE, qMax<SIZE_T>(SIZE_T(data.size()), 1));
    if (!memory) return nullptr;
    if (auto* target = GlobalLock(memory)) {
        if (!data.isEmpty()) std::memcpy(target, data.constData(), size_t(data.size()));
        GlobalUnlock(memory);
        return memory;
    }
    GlobalFree(memory);
    return nullptr;
}

inline bool openClipboard(HWND owner) {
    for (int attempt = 0; attempt < 20; ++attempt) {
        if (OpenClipboard(owner)) return true;
        Sleep(15);
    }
    return false;
}

inline QString formatName(UINT format) {
    if (format < 0xC000) return {};
    wchar_t name[maxFormatName + 1]{};
    const int length = GetClipboardFormatNameW(format, name, maxFormatName + 1);
    return length > 0 ? QString::fromWCharArray(name, length) : QString();
}
}  // namespace RhClipboardWire

struct RhClipboardReply {
    int status = 0;
    QByteArray contentType;
    QMap<QByteArray, QByteArray> headers;  // Lower-case names.
    QByteArray body;
    QString error;
    bool ok() const { return status >= 200 && status < 300; }
    QJsonObject json() const { return QJsonDocument::fromJson(body).object(); }
};

// Minimal blocking HTTPS/1.1 client pinned to the paired host certificate; every wait is abortable.
struct RhClipboardHttp {
    QString host;
    QSslCertificate pinned;
    int port = 0;

    RhClipboardReply exchange(const QByteArray& method, const QByteArray& target, const QByteArray& body, const QByteArray& contentType,
                              int totalMs, const std::atomic<bool>& abort, qint64 maxBody,
                              const std::function<void(qint64)>& sent = {}) const {
        RhClipboardReply reply;
        if (host.isEmpty() || pinned.isNull() || port <= 0) { reply.error = "缺少主机配对信息"; return reply; }
        QSslSocket socket;
        socket.setProxy(QNetworkProxy::NoProxy);
        socket.setSslConfiguration(IdentityManager::get()->getSslConfig());
        QObject::connect(&socket, &QSslSocket::sslErrors, &socket, [&](const QList<QSslError>& errors) {
            if (socket.peerCertificate() == pinned) socket.ignoreSslErrors(errors);
        });
        QElapsedTimer total, idle;
        total.start(); idle.start();
        const auto expired = [&] { return abort.load() || total.elapsed() > totalMs || idle.elapsed() > RhClipboardWire::idleMs; };
        socket.connectToHostEncrypted(host, quint16(port));
        while (!socket.isEncrypted()) {
            if (expired() || idle.elapsed() > 8000 || socket.state() == QAbstractSocket::UnconnectedState) {
                reply.error = abort.load() ? "已取消" : "无法连接远端剪贴板";
                return reply;
            }
            socket.waitForEncrypted(200);
        }
        if (socket.peerCertificate() != pinned) { socket.abort(); reply.error = "主机证书与配对记录不一致"; return reply; }
        QByteArray head = method + " " + target + " HTTP/1.1\r\nHost: RHRemote\r\nConnection: close\r\nContent-Length: " +
                          QByteArray::number(body.size()) + "\r\n";
        if (!contentType.isEmpty()) head += "Content-Type: " + contentType + "\r\n";
        socket.write(head + "\r\n");
        qint64 queued = socket.write(body);
        if (queued != body.size()) { reply.error = "发送失败"; return reply; }
        qint64 lastPending = socket.bytesToWrite();
        idle.restart();
        while (socket.bytesToWrite() > 0) {
            if (expired() || socket.state() != QAbstractSocket::ConnectedState) { reply.error = abort.load() ? "已取消" : "发送超时"; return reply; }
            socket.waitForBytesWritten(200);
            const auto pending = socket.bytesToWrite();
            if (pending < lastPending) {
                idle.restart();
                if (sent) sent(qMax<qint64>(0, body.size() - pending));
                lastPending = pending;
            }
        }
        if (sent) sent(body.size());
        QByteArray data;
        qint64 expected = -1;
        int headerEnd = -1;
        idle.restart();
        for (;;) {
            if (socket.bytesAvailable() || socket.waitForReadyRead(200)) {
                data += socket.readAll();
                idle.restart();
            } else if (socket.state() == QAbstractSocket::UnconnectedState) {
                break;
            } else if (expired()) {
                reply.error = abort.load() ? "已取消" : "远端响应超时";
                return reply;
            }
            if (headerEnd < 0) {
                headerEnd = data.indexOf("\r\n\r\n");
                if (headerEnd < 0) {
                    if (data.size() > 65536) { reply.error = "远端响应无效"; return reply; }
                    continue;
                }
                const auto lines = data.left(headerEnd).split('\n');
                const auto first = lines.value(0).trimmed().split(' ');
                reply.status = first.value(1).toInt();
                for (int i = 1; i < lines.size(); ++i) {
                    const auto line = lines[i].trimmed();
                    const int colon = line.indexOf(':');
                    if (colon > 0) reply.headers[line.left(colon).trimmed().toLower()] = line.mid(colon + 1).trimmed();
                }
                reply.contentType = reply.headers.value("content-type");
                bool valid = false;
                expected = reply.headers.value("content-length").toLongLong(&valid);
                if (!valid || expected < 0 || expected > maxBody) { reply.error = "远端响应过大或无效"; reply.status = 0; return reply; }
            }
            if (headerEnd >= 0 && data.size() - headerEnd - 4 >= expected) break;
        }
        if (headerEnd < 0 || data.size() - headerEnd - 4 < expected) { reply.status = 0; reply.error = "远端响应不完整"; return reply; }
        reply.body = data.mid(headerEnd + 4, int(expected));
        return reply;
    }
};

class RhClipboardSync {
public:
    static RhClipboardSync& get() { static RhClipboardSync instance; return instance; }

    static bool enabledByProfile() { return qEnvironmentVariable("RHREMOTE_CLIPBOARD_SYNC") != "0"; }

    // Called when the stream starts; host details come from the paired session.
    void start(const QString& host, const QSslCertificate& certificate, quint16 gameStreamPort) {
        std::lock_guard<std::mutex> guard(lifecycle);
        if (running) return;
        {
            QMutexLocker lock(&stateMutex);
            status = QJsonObject{{"enabled", enabledByProfile()}, {"supported", "unknown"}, {"phase", "idle"}};
        }
        if (!enabledByProfile() || !gameStreamPort) return;
        const int offset = host == "127.77.0.2" ? qEnvironmentVariableIntValue("RHREMOTE_TUNNEL_PORT_OFFSET") : 0;
        http = RhClipboardHttp{host, certificate, int(gameStreamPort) + (gameStreamPort == 47984 ? offset : 0)};
        abort = false;
        running = true;
        epoch = 0; cursor = 0;
        windowReady = false;
        windowThread.reset(QThread::create([this] { windowMain(); }));
        windowThread->start();
        {
            std::unique_lock<std::mutex> lock(windowMutex);
            windowCv.wait_for(lock, std::chrono::seconds(5), [this] { return windowReady; });
        }
        pollThread.reset(QThread::create([this] { pollMain(); }));
        pollThread->start();
        announceThread.reset(QThread::create([this] { announceMain(); }));
        announceThread->start();
    }

    // Called when the stream ends; all network waits observe the abort flag within a fraction of a second.
    void stop() {
        std::lock_guard<std::mutex> guard(lifecycle);
        if (!running) return;
        abort = true;
        announceCv.notify_all();
        const HWND window = hwnd.load();
        if (window) PostMessageW(window, msgQuit, 0, 0);
        // Every network wait observes the abort flag; only a hung foreign clipboard owner could delay a thread.
        const auto join = [](std::unique_ptr<QThread>& thread) {
            if (thread && !thread->wait(30000)) (void)thread.release();  // Never destroy a running QThread.
            thread.reset();
        };
        join(pollThread);
        join(announceThread);
        join(windowThread);
        std::vector<std::unique_ptr<QThread>> finished;
        {
            std::lock_guard<std::mutex> lock(workersMutex);
            finished.swap(workers);
            uploads.clear();
        }
        for (auto& worker : finished) join(worker);
        running = false;
    }

    QJsonObject state() {
        QMutexLocker lock(&stateMutex);
        return status;
    }

    // Cancels uploads and background downloads; the current paste fails, later copies work normally.
    bool cancelTransfers() {
        cancelGeneration++;
        setPhase("idle", 0, 0, false);
        notice("已取消剪贴板传输");
        return true;
    }

private:
    static constexpr UINT msgRemoteFormats = WM_APP + 0x61;
    static constexpr UINT msgLate = WM_APP + 0x62;
    static constexpr UINT msgAnnounceNow = WM_APP + 0x63;
    static constexpr UINT msgQuit = WM_APP + 0x64;
    static constexpr UINT_PTR announceTimer = 1;

    struct RemoteFormats { quint64 seq = 0; QVector<RhClipboardWire::Format> formats; };
    struct LateData { quint64 generation = 0; UINT format = 0; QByteArray data; };

    std::mutex lifecycle;
    bool running = false;
    RhClipboardHttp http;
    std::atomic<bool> abort{false};
    std::atomic<quint64> cancelGeneration{0};
    std::atomic<HWND> hwnd{nullptr};
    std::unique_ptr<QThread> windowThread, pollThread, announceThread;
    std::mutex windowMutex;
    std::condition_variable windowCv;
    bool windowReady = false;

    // Announce slot: only the newest local clipboard matters.
    std::mutex announceMutex;
    std::condition_variable announceCv;
    std::optional<QVector<RhClipboardWire::Format>> pendingAnnounce;
    std::atomic<bool> supported{false};

    std::mutex workersMutex;
    std::vector<std::unique_ptr<QThread>> workers;
    std::map<quint64, std::shared_ptr<std::atomic<bool>>> uploads;  // Host render requests in progress.

    // Window-thread state.
    std::map<UINT, quint32> mapping;  // Local delayed format -> host format.
    quint64 remoteSeq = 0;
    quint64 generation = 0;

    quint64 epoch = 0, cursor = 0;

    QMutex stateMutex;
    QJsonObject status;

    // ---- state reporting -------------------------------------------------------------------------
    void setState(const QString& key, const QJsonValue& value) { QMutexLocker lock(&stateMutex); status[key] = value; }
    void setPhase(const QString& phase, qint64 bytes, qint64 total, bool background) {
        QMutexLocker lock(&stateMutex);
        status["phase"] = phase;
        status["bytes"] = double(bytes);
        status["total"] = double(total);
        status["background"] = background;
    }
    void notice(const QString& message, const QString& error = QString()) {
        QMutexLocker lock(&stateMutex);
        status["message"] = message;
        status["error"] = error;
        status["atMs"] = double(QDateTime::currentMSecsSinceEpoch());
    }

    // ---- local clipboard window --------------------------------------------------------------------
    static LRESULT CALLBACK windowProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
        if (message == WM_NCCREATE) {
            SetWindowLongPtrW(window, GWLP_USERDATA, LONG_PTR(reinterpret_cast<CREATESTRUCTW*>(lparam)->lpCreateParams));
        }
        if (auto* self = reinterpret_cast<RhClipboardSync*>(GetWindowLongPtrW(window, GWLP_USERDATA))) {
            return self->handle(window, message, wparam, lparam);
        }
        return DefWindowProcW(window, message, wparam, lparam);
    }

    void windowMain() {
        WNDCLASSEXW wc{sizeof(wc)};
        wc.lpfnWndProc = &RhClipboardSync::windowProc;
        wc.hInstance = GetModuleHandleW(nullptr);
        wc.lpszClassName = L"RHRemoteClipboardClient";
        RegisterClassExW(&wc);
        const HWND window = CreateWindowExW(0, wc.lpszClassName, L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, wc.hInstance, this);
        if (window) AddClipboardFormatListener(window);
        hwnd = window;
        {
            std::lock_guard<std::mutex> lock(windowMutex);
            windowReady = true;
        }
        windowCv.notify_all();
        if (!window) return;
        MSG message;
        while (GetMessageW(&message, nullptr, 0, 0) > 0) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        RemoveClipboardFormatListener(window);
        hwnd = nullptr;
        mapping.clear();
        DestroyWindow(window);  // Unrendered remote formats disappear with the stream.
    }

    LRESULT handle(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
        switch (message) {
        case WM_CLIPBOARDUPDATE:
            if (GetClipboardOwner() != window) SetTimer(window, announceTimer, 150, nullptr);
            return 0;
        case WM_TIMER:
            if (wparam == announceTimer) { KillTimer(window, announceTimer); collectLocal(window); }
            return 0;
        case msgAnnounceNow:
            if (GetClipboardOwner() != window) collectLocal(window);
            return 0;
        case msgRemoteFormats: {
            std::unique_ptr<RemoteFormats> remote(reinterpret_cast<RemoteFormats*>(lparam));
            mirrorRemote(window, *remote);
            return 0;
        }
        case WM_RENDERFORMAT:
            renderRemote(window, UINT(wparam));
            return 0;
        case WM_RENDERALLFORMATS:
            return 0;  // The stream is ending; do not block on network transfers.
        case WM_DESTROYCLIPBOARD:
            generation++;
            mapping.clear();
            return 0;
        case msgLate: {
            std::unique_ptr<LateData> late(reinterpret_cast<LateData*>(lparam));
            if (late->generation == generation && GetClipboardOwner() == window && RhClipboardWire::openClipboard(window)) {
                if (const auto memory = RhClipboardWire::toGlobal(late->data); memory && !SetClipboardData(late->format, memory)) GlobalFree(memory);
                CloseClipboard();
                notice("远端剪贴板内容已就绪，请再次粘贴");
            }
            return 0;
        }
        case msgQuit:
            PostQuitMessage(0);
            return 0;
        default:
            return DefWindowProcW(window, message, wparam, lparam);
        }
    }

    void collectLocal(HWND window) {
        if (!RhClipboardWire::openClipboard(window)) { SetTimer(window, announceTimer, 500, nullptr); return; }
        QVector<RhClipboardWire::Format> available;
        for (UINT format = EnumClipboardFormats(0); format; format = EnumClipboardFormats(format)) {
            available.push_back({format, RhClipboardWire::formatName(format)});
        }
        CloseClipboard();
        auto selected = RhClipboardWire::select(available);
        if (selected.isEmpty()) return;  // Never wipe the remote clipboard just because nothing transferable is here.
        {
            std::lock_guard<std::mutex> lock(announceMutex);
            pendingAnnounce = std::move(selected);
        }
        announceCv.notify_all();
    }

    void mirrorRemote(HWND window, const RemoteFormats& remote) {
        if (!RhClipboardWire::openClipboard(window)) { notice(QString(), "本机剪贴板被占用，未能同步远端内容"); return; }
        if (!EmptyClipboard()) { CloseClipboard(); return; }  // Clears any previous mirror through WM_DESTROYCLIPBOARD.
        generation++;
        mapping.clear();
        remoteSeq = remote.seq;
        for (const auto& format : remote.formats) {
            const UINT local = format.name.isEmpty() ? format.id : RegisterClipboardFormatW(reinterpret_cast<const wchar_t*>(format.name.utf16()));
            if (!local || mapping.count(local)) continue;
            SetLastError(ERROR_SUCCESS);
            if (SetClipboardData(local, nullptr) || GetLastError() == ERROR_SUCCESS) mapping[local] = format.id;
        }
        CloseClipboard();
        setState("lastDirection", "toLocal");
        notice("已同步远端剪贴板");
    }

    // Answer a local paste of host data within the budget; afterwards continue in the background.
    void renderRemote(HWND, UINT format) {
        const auto found = mapping.find(format);
        if (found == mapping.end()) return;
        const quint64 seq = remoteSeq, gen = generation, cancelAt = cancelGeneration.load();
        const quint32 remote = found->second;
        QByteArray data;
        qint64 total = -1;
        QElapsedTimer budget;
        budget.start();
        const auto outcome = download(seq, remote, data, total, cancelAt, [&] { return RhClipboardWire::renderBudgetMs - budget.elapsed(); });
        if (outcome == Download::Complete) {
            if (const auto memory = RhClipboardWire::toGlobal(data); memory && !SetClipboardData(format, memory)) GlobalFree(memory);
            setPhase("idle", 0, 0, false);
            return;
        }
        if (outcome != Download::Budget) { setPhase("idle", 0, 0, false); return; }
        notice("远端内容较大，正在后台传输，完成后可再次粘贴");
        auto partial = std::make_shared<QByteArray>(std::move(data));
        spawn([this, seq, remote, format, gen, cancelAt, partial, total]() mutable {
            QByteArray buffer = std::move(*partial);
            qint64 size = total;
            if (download(seq, remote, buffer, size, cancelAt, [] { return qint64(1) << 40; }) != Download::Complete) {
                setPhase("idle", 0, 0, false);
                return;
            }
            setPhase("idle", 0, 0, false);
            auto late = std::make_unique<LateData>(LateData{gen, format, std::move(buffer)});
            const HWND window = hwnd.load();
            if (window && PostMessageW(window, msgLate, 0, LPARAM(late.get()))) late.release();
        });
    }

    enum class Download { Complete, Budget, Failed };

    Download download(quint64 seq, quint32 format, QByteArray& data, qint64& total, quint64 cancelAt, const std::function<qint64()>& remainingMs) {
        while (total < 0 || data.size() < total) {
            if (abort.load() || cancelGeneration.load() != cancelAt) return Download::Failed;
            const qint64 remaining = remainingMs();
            if (remaining <= 0) return Download::Budget;
            const QByteArray target = "/rhremote/clipboard/data?v=1&seq=" + QByteArray::number(seq) + "&format=" + QByteArray::number(format) +
                                      "&offset=" + QByteArray::number(data.size());
            const auto reply = http.exchange("GET", target, {}, {}, int(qMin<qint64>(remaining, 3600000)), abort, RhClipboardWire::chunkSize + 1);
            if (!reply.ok()) {
                if (reply.status == 0 && !abort.load() && remainingMs() <= 0) return Download::Budget;
                notice(QString(), reply.status ? reply.json().value("error").toString("无法读取远端剪贴板") : reply.error);
                return Download::Failed;
            }
            bool valid = false;
            const qint64 declared = reply.headers.value("x-rhremote-total").toLongLong(&valid);
            if (!valid || declared < 0 || (total >= 0 && declared != total) || reply.headers.value("x-rhremote-offset").toLongLong() != data.size()) {
                notice(QString(), "远端剪贴板响应无效");
                return Download::Failed;
            }
            if (total < 0) {
                if (quint64(declared) > RhClipboardWire::memoryBudget()) { notice(QString(), "本机内存不足，无法接收远端剪贴板内容"); return Download::Failed; }
                total = declared;
                data.reserve(int(qMin<qint64>(total, (std::numeric_limits<int>::max)())));
            }
            if (reply.body.isEmpty() && data.size() < total) { notice(QString(), "远端剪贴板响应无效"); return Download::Failed; }
            data += reply.body;
            setPhase("toLocal", data.size(), total, remainingMs() > qint64(1) << 39);
        }
        return Download::Complete;
    }

    void spawn(std::function<void()> work) {
        std::lock_guard<std::mutex> lock(workersMutex);
        for (auto it = workers.begin(); it != workers.end();) {
            if ((*it)->isFinished()) it = workers.erase(it); else ++it;
        }
        if (abort.load()) return;
        workers.emplace_back(QThread::create(std::move(work)));
        workers.back()->start();
    }

    // ---- network: announcements --------------------------------------------------------------------
    void announceMain() {
        while (!abort.load()) {
            QVector<RhClipboardWire::Format> formats;
            {
                std::unique_lock<std::mutex> lock(announceMutex);
                announceCv.wait(lock, [this] { return abort.load() || (pendingAnnounce && supported.load()); });
                if (abort.load()) return;
                formats = *pendingAnnounce;
                pendingAnnounce.reset();
            }
            const auto body = QJsonDocument(QJsonObject{{"version", RhClipboardWire::version}, {"formats", RhClipboardWire::toJson(formats)}})
                                  .toJson(QJsonDocument::Compact);
            for (int attempt = 0; attempt < 4 && !abort.load(); ++attempt) {
                const auto reply = http.exchange("POST", "/rhremote/clipboard/formats", body, "application/json", 15000, abort, 65536);
                if (reply.ok()) {
                    setState("lastDirection", "toRemote");
                    notice(formats.isEmpty() ? QString() : "已同步本机剪贴板");
                    break;
                }
                {
                    std::lock_guard<std::mutex> lock(announceMutex);
                    if (pendingAnnounce) break;  // A newer copy supersedes this one.
                }
                QThread::msleep(500);
            }
        }
    }

    // ---- network: host events ----------------------------------------------------------------------
    void pollMain() {
        int backoff = 500;
        while (!abort.load()) {
            const QByteArray target = "/rhremote/clipboard/events?v=1&epoch=" + QByteArray::number(epoch) + "&cursor=" + QByteArray::number(cursor) +
                                      "&wait=" + QByteArray::number(supported.load() ? RhClipboardWire::pollWaitMs : 0);
            const auto reply = http.exchange("GET", target, {}, {}, RhClipboardWire::pollWaitMs + 15000, abort, 1 << 20);
            if (abort.load()) return;
            if (reply.status == 404 || (reply.ok() && !reply.contentType.startsWith("application/json"))) {
                setState("supported", "no");
                notice(QString(), "远端 RHRemote-Sunshine 版本不支持剪贴板同步");
                return;  // Older host: the manual paste shortcuts remain available.
            }
            if (!reply.ok()) {
                QThread::msleep(backoff);
                backoff = qMin(backoff * 2, 5000);
                continue;
            }
            backoff = 500;
            const auto object = reply.json();
            if (!supported.exchange(true)) {
                setState("supported", "yes");
                const HWND window = hwnd.load();
                if (window) PostMessageW(window, msgAnnounceNow, 0, 0);  // Local clipboard wins when the stream starts.
                announceCv.notify_all();
            }
            const quint64 replyEpoch = quint64(object.value("epoch").toDouble());
            if (replyEpoch != epoch) { epoch = replyEpoch; cursor = 0; }
            if (object.contains("cursor")) cursor = quint64(object.value("cursor").toDouble());
            for (const auto& item : object.value("events").toArray()) handleEvent(item.toObject());
        }
    }

    void handleEvent(const QJsonObject& event) {
        const auto type = event.value("type").toString();
        if (type == "formats") {
            auto formats = RhClipboardWire::parse(event.value("formats"));
            const HWND window = hwnd.load();
            if (!formats || formats->isEmpty() || !window) return;
            auto remote = std::make_unique<RemoteFormats>(RemoteFormats{quint64(event.value("seq").toDouble()), *formats});
            if (PostMessageW(window, msgRemoteFormats, 0, LPARAM(remote.get()))) remote.release();
        } else if (type == "render") {
            const quint64 request = quint64(event.value("request").toDouble());
            const quint32 format = quint32(event.value("format").toDouble());
            auto cancelled = std::make_shared<std::atomic<bool>>(false);
            {
                std::lock_guard<std::mutex> lock(workersMutex);
                uploads[request] = cancelled;
            }
            spawn([this, request, format, cancelled] { upload(request, format, *cancelled); });
        } else if (type == "cancel") {
            std::lock_guard<std::mutex> lock(workersMutex);
            const auto found = uploads.find(quint64(event.value("request").toDouble()));
            if (found != uploads.end()) *found->second = true;
        }
    }

    // Answer a host paste with local clipboard data, in chunks.
    void upload(quint64 request, quint32 format, std::atomic<bool>& cancelled) {
        const quint64 cancelAt = cancelGeneration.load();
        const QByteArray base = "/rhremote/clipboard/render?v=1&request=" + QByteArray::number(request);
        const auto finish = [&] {
            std::lock_guard<std::mutex> lock(workersMutex);
            uploads.erase(request);
        };
        QByteArray data;
        bool readable = false;
        const HWND own = hwnd.load();
        if (RhClipboardWire::transferable({format, RhClipboardWire::formatName(format)}) && GetClipboardOwner() != own &&
            RhClipboardWire::openClipboard(nullptr)) {
            if (const auto memory = GetClipboardData(format)) {
                const SIZE_T size = GlobalSize(memory);
                const auto* source = static_cast<const char*>(GlobalLock(memory));
                if ((source || !size) && quint64(size) <= RhClipboardWire::maxTotal) {
                    data = QByteArray(source, int(qMin<SIZE_T>(size, SIZE_T((std::numeric_limits<int>::max)()))));
                    readable = data.size() == qint64(size);
                }
                if (source) GlobalUnlock(memory);
            }
            CloseClipboard();
        }
        if (!readable) {
            http.exchange("POST", base + "&error=1", {}, {}, 15000, abort, 65536);
            finish();
            return;
        }
        const qint64 total = data.size();
        qint64 offset = 0;
        do {
            if (abort.load() || cancelled.load() || cancelGeneration.load() != cancelAt) break;
            const auto chunk = data.mid(int(offset), int(qMin<qint64>(RhClipboardWire::chunkSize, total - offset)));
            const auto reply = http.exchange("POST", base + "&offset=" + QByteArray::number(offset) + "&total=" + QByteArray::number(total), chunk,
                                             "application/octet-stream", 3600000, abort, 65536, [&](qint64 sent) {
                                                 setPhase("toRemote", offset + sent, total, false);
                                             });
            if (!reply.ok()) {
                if (reply.status != 410) notice(QString(), reply.status ? reply.json().value("error").toString("发送到远端失败") : reply.error);
                break;
            }
            offset += chunk.size();
            if (reply.json().value("complete").toBool()) {
                setState("lastDirection", "toRemote");
                break;
            }
        } while (offset < total);
        setPhase("idle", 0, 0, false);
        finish();
    }
};
#endif
