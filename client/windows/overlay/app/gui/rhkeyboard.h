#pragma once
#include <QFile>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QSet>
namespace RhKeyboard {
inline QJsonObject command(const QJsonObject& body){
    static const auto catalog=[](){QFile file(":/res/rhremote-keyboard.json");if(!file.open(QIODevice::ReadOnly))return QJsonArray{};return QJsonDocument::fromJson(file.readAll()).array();}();
    QJsonObject key;for(const auto& entry:catalog)if(body["key"].isString()&&entry.toObject()["id"]==body["key"]){key=entry.toObject();break;}
    if(key.isEmpty())return {{"ok",false},{"error","unknown keyboard key"}};
    if(!body["modifiers"].isArray()||body["modifiers"].toArray().size()>4)return {{"ok",false},{"error","invalid keyboard modifiers"}};
    QSet<QString> seen;QJsonArray keys;
    for(const auto& value:body["modifiers"].toArray()){
        const auto modifier=value.toString();const int code=modifier=="shift"?16:modifier=="ctrl"?17:modifier=="alt"?18:modifier=="meta"?91:0;
        if(!value.isString()||!code||seen.contains(modifier))return {{"ok",false},{"error","invalid keyboard modifier"}};
        seen.insert(modifier);keys.append(code);
    }
    keys.append(key["code"].toInt());
    return {{"ok",true},{"keys",keys},{"secureAttention",key["id"]=="delete"&&seen.contains("ctrl")&&seen.contains("alt")}};
}
}
