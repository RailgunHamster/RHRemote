#include "client/windows/overlay/app/gui/rhwindowsource.h"
#include "client/windows/overlay/app/gui/rhdisplaylaunch.h"
#include <QCoreApplication>
#include <cstdio>
#include <cstdlib>

static void require(bool value){if(!value)std::abort();}
int main(int argc,char** argv){
    QCoreApplication app(argc,argv);
    const QString source="rhrwindow:0123456789abcdef0123456789abcdef";
    const auto row=[&](const QString& id,const QString& state="ready",const QString& width="1200"){return "<Window><id>"+id+"</id><title>A &amp; B</title><application>editor.exe</application><state>"+state+"</state><width>"+width+"</width><height>800</height></Window>";};
    const auto xml=[](const QString& rows){return "<root status_code=\"200\"><supported>1</supported>"+rows+"</root>";};
    auto parsed=RhWindowSource::parse(xml(row(source)+row("","permission_denied","0")));
    require(parsed["ok"].toBool());auto rows=parsed["result"].toObject()["windows"].toArray();require(rows.size()==2);require(rows[0].toObject()["title"].toString()=="A & B");require(rows[0].toObject()["available"].toBool());require(!rows[1].toObject()["available"].toBool());
    for(const auto& bad:QStringList{"",xml(row(source)+row(source)),xml(row(source,"ready","-1")),xml(row("0x123")),"<root status_code=\"401\"/>","<root status_code=\"200\"><supported>0</supported></root>",xml("<Window>")})require(!RhWindowSource::parse(bad)["ok"].toBool());
    for(const auto& bad:QStringList{source.toUpper(),source+"0","rhrwindow:١١١١١١١١١١١١١١١١١١١١١١١١١١١١١١١١"})require(!RhWindowSource::valid(bad));
    require(RhDisplayLaunch::valid(source));require(RhDisplayLaunch::query(source).contains("rhrwindow%3A"));
    require(RhDisplayLaunch::acknowledge(source,source)==RhDisplayLaunch::Reply::Selected);
    require(RhDisplayLaunch::acknowledge(source,"")==RhDisplayLaunch::Reply::Invalid);
    require(RhDisplayLaunch::acknowledge(source,"\\\\.\\DISPLAY1")==RhDisplayLaunch::Reply::Invalid);
    require(RhDisplayLaunch::acknowledge("\\\\.\\DISPLAY1","")==RhDisplayLaunch::Reply::Legacy);
    puts("PASS: native window XML, canonical selectors, and strict window launch acknowledgements");
}
