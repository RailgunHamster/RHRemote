#include "client/windows/overlay/app/gui/rhkeyboard.h"
#include <QCoreApplication>
#include <cstdio>
int main(int argc,char** argv){
    QCoreApplication app(argc,argv);QFile file(":/res/rhremote-keyboard.json");if(!file.open(QIODevice::ReadOnly))return 1;
    const auto keys=QJsonDocument::fromJson(file.readAll()).array();if(keys.size()!=108)return 2;
    for(const auto& entry:keys){const auto key=entry.toObject();const auto result=RhKeyboard::command({{"key",key["id"]},{"modifiers",QJsonArray{}}});if(!result["ok"].toBool()||result["keys"].toArray()!=QJsonArray{key["code"]})return 3;}
    const auto chord=RhKeyboard::command({{"key","c"},{"modifiers",QJsonArray{"ctrl"}},{"keys",QJsonArray{255}}});if(chord["keys"].toArray()!=QJsonArray{17,67})return 4;
    if(!RhKeyboard::command({{"key","delete"},{"modifiers",QJsonArray{"ctrl","alt"}}})["secureAttention"].toBool())return 5;
    for(const auto& bad:QJsonArray{QJsonObject{{"key","unknown"},{"modifiers",QJsonArray{}}},QJsonObject{{"key","a"}},QJsonObject{{"key","a"},{"modifiers",QJsonArray{"bad"}}},QJsonObject{{"key","a"},{"modifiers",QJsonArray{"ctrl","ctrl"}}},QJsonObject{{"key","a"},{"modifiers",QJsonArray{1}}}}){if(RhKeyboard::command(bad.toObject())["ok"].toBool())return 6;}
    std::puts("PASS: 108 native keys, validated chords, untrusted payload rejection and secure attention routing");return 0;
}
