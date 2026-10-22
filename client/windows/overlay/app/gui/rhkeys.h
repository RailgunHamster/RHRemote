#pragma once
#include <QJsonArray>
#include <QJsonObject>
#include <QMutex>
#include <QMutexLocker>
#include <QThread>
#include <Limelight.h>
namespace RhKeys {
inline QJsonObject send(const QJsonArray& keys){
    static QMutex mutex;QMutexLocker lock(&mutex);int result=0,attempted=0;char modifiers=0;bool released=true;
    auto flag=[](int key)->char{return key==16?MODIFIER_SHIFT:key==17?MODIFIER_CTRL:key==18?MODIFIER_ALT:key==91?MODIFIER_META:0;};
    if(keys.isEmpty()||keys.size()>5)return {{"ok",false},{"error","invalid shortcut"}};
    for(const auto& entry:keys)if(entry.toInt()<1||entry.toInt()>255)return {{"ok",false},{"error","invalid shortcut"}};
    for(const auto& entry:keys){const int key=entry.toInt();modifiers|=flag(key);attempted++;const int code=LiSendKeyboardEvent(short(0x8000|key),KEY_ACTION_DOWN,modifiers);if(code!=0){result=code;break;}}
    QThread::msleep(45);
    for(int i=attempted-1;i>=0;i--){const int key=keys[i].toInt();modifiers&=~flag(key);const int code=LiSendKeyboardEvent(short(0x8000|key),KEY_ACTION_UP,modifiers);released=code==0&&released;if(result==0&&code!=0)result=code;}
    return {{"ok",result==0},{"keysReleased",released},{"sendResult",result},{"error",result==0?"":"remote input transport rejected shortcut"}};
}
inline QJsonObject display(int index){if(index<1||index>12)return {{"ok",false},{"error","Sunshine supports monitors 1 through 12"}};return send(QJsonArray{17,18,16,111+index});}
}
