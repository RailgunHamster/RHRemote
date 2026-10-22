#pragma once
#include <QObject>
#include <QSettings>
#include <QVariantMap>
#include <QSortFilterProxyModel>
#include <QGuiApplication>
#include <QScreen>
#include <QWindow>
#include <QDebug>
#include "settings/streamingpreferences.h"
#include "rhcontrol.h"

class RhProfiles : public QObject {
    Q_OBJECT
public:
    explicit RhProfiles(QObject* parent=nullptr):QObject(parent){}
    Q_INVOKABLE bool apiControlled()const{return qEnvironmentVariableIsSet("RHREMOTE_CONTROL_PORT");}
    Q_INVOKABLE bool pairingControlled()const{return qEnvironmentVariableIsSet("RHREMOTE_PAIR_OPERATION");}
    Q_INVOKABLE void connectionStatus(const QString& phase,const QString& error=QString())const{RhRuntime::status(phase,error);}
    Q_INVOKABLE void placeWindow(QWindow* window) const {
        if(!window || qEnvironmentVariable("RHREMOTE_UI_SCREEN")!="secondary")return;
        const auto screens=QGuiApplication::screens();
        QList<QScreen*> candidates;
        for(auto* screen:screens)if(screen!=QGuiApplication::primaryScreen())candidates.append(screen);
        // This workstation also has a tablet virtual display on the right. The user's
        // physical test monitor is the only display left of the primary monitor.
        if(candidates.size()!=1){
            candidates.clear();
            for(auto* screen:screens)if(screen->geometry().right()<QGuiApplication::primaryScreen()->geometry().left())candidates.append(screen);
        }
        if(candidates.size()!=1){qWarning("RHRemote secondary screen is ambiguous; retaining the current position");return;}
        auto* screen=candidates.first();
        {
            const QRect rect=screen->availableGeometry();window->setScreen(screen);
            window->setGeometry(rect.x()+40,rect.y()+40,qMin(1180,rect.width()-80),qMin(780,rect.height()-80));
            window->showNormal();
            qInfo()<<"RHRemote UI screen"<<screen->name()<<screen->geometry()<<"window"<<window->geometry();
        }
    }
    Q_INVOKABLE QVariantMap load(const QString& uuid) const {
        QSettings s;s.beginGroup("rhremote/devices/"+uuid);
        QVariantMap p;
        p["width"]=s.value("width",2520);p["height"]=s.value("height",1680);
        p["fps"]=s.value("fps",30);p["bitrateMbps"]=s.value("bitrateMbps",25);
        p["codec"]=s.value("codec","HEVC");p["swapButtons"]=s.value("swapButtons",false);
        p["statistics"]=s.value("statistics",false);return p;
    }
    Q_INVOKABLE bool save(const QString& uuid,const QVariantMap& p) {
        if(uuid.isEmpty())return false;
        int w=p.value("width").toInt(),h=p.value("height").toInt(),fps=p.value("fps").toInt(),rate=p.value("bitrateMbps").toInt();
        if(w<640||w>7680||h<360||h>4320||(fps!=30&&fps!=60)||rate<5||rate>150)return false;
        QSettings s;s.beginGroup("rhremote/devices/"+uuid);
        for(const QString& key:{"width","height","fps","bitrateMbps","codec","swapButtons","statistics"})s.setValue(key,p.value(key));
        s.sync();return s.status()==QSettings::NoError;
    }
    Q_INVOKABLE void apply(const QString& uuid) {
        QVariantMap p=load(uuid);auto* prefs=StreamingPreferences::get();
        prefs->width=p["width"].toInt();prefs->height=p["height"].toInt();prefs->fps=p["fps"].toInt();
        prefs->bitrateKbps=p["bitrateMbps"].toInt()*1000;
        prefs->videoCodecConfig=p["codec"].toString()=="H.264"?StreamingPreferences::VCC_FORCE_H264:StreamingPreferences::VCC_FORCE_HEVC;
        prefs->playAudioOnHost=true;prefs->enableHdr=false;prefs->enableVsync=true;prefs->framePacing=true;
        prefs->showPerformanceOverlay=p["statistics"].toBool();prefs->swapMouseButtons=p["swapButtons"].toBool();
        prefs->absoluteMouseMode=true;prefs->absoluteTouchMode=true;prefs->quitAppAfter=false;
        prefs->windowMode=StreamingPreferences::WM_WINDOWED;
        // Apply only in memory before Session snapshots preferences. Profiles never overwrite each other.
    }
};

class RhHosts : public QSortFilterProxyModel {
    Q_OBJECT
    Q_PROPERTY(QAbstractItemModel* source READ sourceModel WRITE setSource)
public:
    explicit RhHosts(QObject* parent=nullptr):QSortFilterProxyModel(parent){setDynamicSortFilter(true);}
    void setSource(QAbstractItemModel* model){setSourceModel(model);sort(0);}
protected:
    bool lessThan(const QModelIndex& a,const QModelIndex& b) const override {
        const QString left=sourceModel()->data(a,Qt::UserRole).toString().toLower();
        const QString right=sourceModel()->data(b,Qt::UserRole).toString().toLower();
        if((left=="winserver")!=(right=="winserver"))return left=="winserver";
        return left<right;
    }
};
