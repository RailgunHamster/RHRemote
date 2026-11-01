#pragma once
#include "rhstatistics.h"
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QMap>
#include <QRegularExpression>
#include <QSettings>
#ifdef Q_OS_WIN
#include <QtCore/qt_windows.h>
#include <tlhelp32.h>
#else
#include <dlfcn.h>
#endif

namespace RhCaptureTools {
// Use only a capture manifest's documented opt-out; never accept arbitrary
// environment keys from local configuration. Changes affect this process only.
inline QMap<QByteArray,QByteArray> optOut(const QJsonObject& manifest){
    const auto layer=manifest["layer"].toObject();
    if(!layer["description"].toString().contains("RenderDoc",Qt::CaseInsensitive))return {};
    const auto values=layer["disable_environment"].toObject();
    const QRegularExpression allowed("^DISABLE_VULKAN_[A-Z0-9_]{1,100}$");
    QMap<QByteArray,QByteArray> result;
    for(auto value=values.begin();value!=values.end();++value){
        const auto text=value.value().toString();
        if(allowed.match(value.key()).hasMatch()&&!text.isEmpty()&&text.size()<=32&&text.toLatin1()==text.toUtf8()&&!text.contains(QChar(0)))
            result.insert(value.key().toLatin1(),text.toLatin1());
    }
    return result;
}
inline void isolate(){
#ifdef Q_OS_WIN
    if(qEnvironmentVariableIsEmpty("RHREMOTE_CONTROL_PORT")||qEnvironmentVariableIntValue("RHREMOTE_ALLOW_GRAPHICS_CAPTURE")==1)return;
    RhStatistics::Scope trace("client_capture_layer_scan");QJsonArray layers;
    for(const auto root:{"HKEY_LOCAL_MACHINE\\SOFTWARE\\Khronos\\Vulkan\\ImplicitLayers","HKEY_CURRENT_USER\\SOFTWARE\\Khronos\\Vulkan\\ImplicitLayers"}){
        QSettings registry(QString::fromLatin1(root),QSettings::NativeFormat);
        for(const auto& name:registry.allKeys()){
            if(registry.value(name).toUInt()!=0||!QFileInfo(name).isAbsolute())continue;
            QFile file(name);if(!file.open(QIODevice::ReadOnly)||file.size()>65536)continue;
            const auto manifest=QJsonDocument::fromJson(file.readAll()).object();const auto values=optOut(manifest);
            for(auto value=values.begin();value!=values.end();++value)qputenv(value.key().constData(),value.value());
            if(!values.isEmpty())layers.append(manifest["layer"].toObject()["name"]);
        }
    }
    if(!layers.isEmpty())RhStatistics::event("graphics_capture_opt_out",-1,{{"layers",layers},{"scope","native_process_only"}});
#endif
}
inline bool attached(){
#ifdef Q_OS_WIN
    HANDLE snapshot=CreateToolhelp32Snapshot(TH32CS_SNAPMODULE,GetCurrentProcessId());
    if(snapshot==INVALID_HANDLE_VALUE)return true;
    MODULEENTRY32W entry{};entry.dwSize=sizeof(entry);bool found=false;unsigned count=0;
    for(BOOL next=Module32FirstW(snapshot,&entry);next&&count++<512;next=Module32NextW(snapshot,&entry)){
        HMODULE module=nullptr;
        if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,reinterpret_cast<LPCWSTR>(entry.modBaseAddr),&module))continue;
        found=GetProcAddress(module,"RENDERDOC_GetAPI")!=nullptr;FreeLibrary(module);if(found)break;
    }
    CloseHandle(snapshot);return found;
#else
    return dlsym(RTLD_DEFAULT,"RENDERDOC_GetAPI")!=nullptr;
#endif
}
}
