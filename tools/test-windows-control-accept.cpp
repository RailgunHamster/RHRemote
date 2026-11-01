#include "client/windows/overlay/app/gui/rhcontrolaccept.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <cstdio>
#include <memory>
#include <vector>

static bool burst(int count) {
    QTcpServer listener;
    if (!listener.listen(QHostAddress::LocalHost, 0)) return false;
    std::vector<std::unique_ptr<QTcpSocket>> clients;
    for (int i = 0; i < count; ++i) {
        auto client = std::make_unique<QTcpSocket>();
        client->connectToHost(QHostAddress::LocalHost, listener.serverPort());
        if (!client->waitForConnected(1000)) return false;
        const auto request = QByteArray("request-") + QByteArray::number(i) + '\n';
        if (client->write(request) != request.size() || !client->waitForBytesWritten(1000)) return false;
        clients.push_back(std::move(client));
    }
    // All clients have connected before the first accept. No later connection
    // can accidentally wake a request left in Qt's pending queue.
    QElapsedTimer clock;
    clock.start();
    for (int i = 0; i < count; ++i) {
        std::unique_ptr<QTcpSocket> accepted(RhControlAccept::next(listener));
        if (!accepted) {
            std::fprintf(stderr, "burst %d: queued client %d was not accepted\n", count, i + 1);
            return false;
        }
        QByteArray request;
        while (!request.contains('\n')) {
            if (!accepted->bytesAvailable() && !accepted->waitForReadyRead(1000)) return false;
            request += accepted->readAll();
        }
        if (request != QByteArray("request-") + QByteArray::number(i) + '\n') return false;
        if (accepted->write("accepted\n") != 9 || !accepted->waitForBytesWritten(1000)) return false;
    }
    if (listener.hasPendingConnections()) return false;
    for (auto& client : clients) {
        QByteArray reply;
        while (!reply.contains('\n')) {
            if (!client->bytesAvailable() && !client->waitForReadyRead(1000)) return false;
            reply += client->readAll();
        }
        if (reply != "accepted\n") return false;
    }
    std::printf("burst %d: all requests and replies completed in %lld ms\n", count, clock.elapsed());
    std::unique_ptr<QTcpSocket> idle(RhControlAccept::next(listener, 20));
    return !idle;
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (!burst(2) || !burst(8)) return 1;
    std::puts("Native control accept regressions passed");
    return 0;
}
