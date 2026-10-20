import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import ComputerModel 1.0
import ComputerManager 1.0
import AppModel 1.0
import RhProfiles 1.0

Item {
    id: home
    objectName: "设备"
    property bool editing: false
    property string selectedUuid: ""
    property string selectedName: ""
    property var profile: ({})
    property string status: ""
    property int revision: 0
    property var pendingApps: null
    property string pendingUuid: ""
    property string pendingName: ""
    property int pendingTicks: 0
    ComputerModel{id: computers}
    RhHosts{id: sortedHosts;source:computers}
    Component.onCompleted: {
        computers.initialize(ComputerManager)
        ComputerManager.computerAddCompleted.connect(function(success,blocked){status=success?"已添加电脑":"电脑不可达，请确认 Sunshine 与网络。"})
    }
    Connections {
        target: computers
        function onPairingCompleted(error){pairDialog.close();status=error?String(error):"配对成功，可以连接电脑。";}
    }
    function summary(uuid){var p=RhProfiles.load(uuid);return p.width+" × "+p.height+" · "+p.fps+" 帧 · "+p.bitrateMbps+" Mbps";}
    function openProfile(uuid,name){selectedUuid=uuid;selectedName=name;profile=RhProfiles.load(uuid);editing=true;}
    function openDefaultProfile(){for(var i=0;i<computers.rowCount();i++){var d=computers.get(i);if(d.name.toLowerCase()==="winserver"){openProfile(d.uuid,d.name);return;}}if(computers.rowCount()>0){var first=computers.get(0);openProfile(first.uuid,first.name);}}
    function option(key,value){var p=JSON.parse(JSON.stringify(profile));p[key]=value;profile=p;}
    function connectComputer(uuid) {
        if(pendingApps){status="正在准备连接，请稍候。";return;}
        for(var i=0;i<computers.rowCount();i++){
            var d=computers.get(i);if(d.uuid!==uuid)continue;
            if(!d.online){status="电脑当前不可达，请检查 Sunshine 与网络。";return;}
            if(!d.paired){var pin=computers.generatePinString();pairCode.text=pin;pairDialog.open();computers.pairComputer(i,pin);return;}
            pendingUuid=uuid;pendingName=d.name;pendingTicks=0;
            pendingApps=Qt.createQmlObject('import AppModel 1.0; AppModel {}',home);
            pendingApps.initialize(ComputerManager,i,false);status="正在连接 "+d.name;launchTimer.start();return;
        }
    }
    Timer {
        id: launchTimer;interval:250;repeat:true
        onTriggered:{
            if(!pendingApps){stop();return;}
            for(var i=0;i<pendingApps.rowCount();i++){
                var app=pendingApps.get(i);if(app.name.toLowerCase()!=="desktop")continue;
                if(pendingApps.getRunningAppId()!==0&&pendingApps.getRunningAppId()!==app.appid){status="主机正在运行其他串流应用，请先结束该会话。";pendingApps.destroy();pendingApps=null;stop();return;}
                RhProfiles.apply(pendingUuid);
                var session=pendingApps.createSessionForApp(i);
                var component=Qt.createComponent("StreamSegue.qml");
                var segue=component.createObject(stackView,{session:session,appName:"Desktop",isResume:app.running});
                pendingApps.destroy();pendingApps=null;stop();status="";stackView.push(segue);return;
            }
            if(++pendingTicks>48){status="没有读取到 Desktop 入口，请检查主机应用列表。";pendingApps.destroy();pendingApps=null;stop();}
        }
    }
    Rectangle {
        width:64;anchors.top:parent.top;anchors.bottom:parent.bottom;color:window.sideColor
        Column {
            anchors.top:parent.top;anchors.topMargin:20;anchors.horizontalCenter:parent.horizontalCenter;spacing:16
            Rectangle {
                width:30;height:30;radius:9
                gradient: Gradient{GradientStop{position:0;color:"#5274e8"}GradientStop{position:1;color:"#8b5be2"}}
                Text{anchors.centerIn:parent;text:"RH";color:"white";font.pixelSize:12;font.weight:Font.Medium}
            }
            RhButton{text:"▦";chosen:!editing;onClicked:editing=false;Accessible.name:"设备"}
            RhButton{text:"＋";onClicked:addDialog.open();Accessible.name:"添加电脑"}
        }
        RhButton{anchors.bottom:parent.bottom;anchors.bottomMargin:22;anchors.horizontalCenter:parent.horizontalCenter;text:"⚙";onClicked:openDefaultProfile();Accessible.name:"连接偏好"}
    }
    Item {
        anchors.left:parent.left;anchors.leftMargin:64;anchors.right:parent.right;anchors.top:parent.top;anchors.bottom:parent.bottom
        ScrollView {
            anchors.fill:parent;anchors.margins:32;clip:true
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
            contentWidth:availableWidth
            ColumnLayout {
                width:parent.width;spacing:12
                RowLayout {
                    Layout.fillWidth:true
                    Label{text:editing?"连接设置":"设备";color:window.mutedColor;font.pixelSize:13}
                    Item{Layout.fillWidth:true}
                    RhButton{text:editing?"返回":"＋ 添加电脑";onClicked:{if(editing)editing=false;else addDialog.open();}}
                }
                Label {
                    Layout.fillWidth:true;Layout.topMargin:editing?2:14
                    text:editing?selectedName:"远程设备";font.pixelSize:editing?25:29;font.weight:Font.Medium
                    horizontalAlignment:editing?Text.AlignLeft:Text.AlignHCenter;color:window.inkColor
                }
                Label{visible:editing;Layout.fillWidth:true;Layout.bottomMargin:20;text:"仅保存这台电脑的连接偏好";horizontalAlignment:Text.AlignLeft;color:window.mutedColor;font.pixelSize:13}
                ColumnLayout {
                    visible:!editing;Layout.fillWidth:true;spacing:12
                    Label{visible:deviceRepeater.count===0;text:"正在发现局域网电脑，也可以直接添加地址。";color:window.mutedColor;wrapMode:Text.Wrap;Layout.fillWidth:true;Layout.topMargin:25;Layout.bottomMargin:25}
                    Repeater {
                        id:deviceRepeater
                        model: sortedHosts
                        delegate: Rectangle {
                            Layout.fillWidth:true;implicitHeight:106;radius:18
                            color:model.name.toLowerCase()==="winserver"?window.blueSoft:window.surfaceColor
                            border.color:model.name.toLowerCase()==="winserver"?window.blueLine:window.lineColor
                            RowLayout {
                                anchors.fill:parent;anchors.margins:18;spacing:14
                                Rectangle{width:44;height:44;radius:12;color:window.blueSoft
                                    Label{anchors.centerIn:parent;text:"\uE7F4";font.family:"Segoe MDL2 Assets";font.pixelSize:22;color:window.primaryText}}
                                ColumnLayout {
                                    Layout.fillWidth:true;spacing:4
                                    RowLayout{spacing:9
                                        Label{text:model.name;color:window.inkColor;font.weight:Font.Medium;font.pixelSize:16}
                                        Label{text:model.statusUnknown?"检查中":!model.online?"离线":model.paired?"● 已配对":"待配对";font.pixelSize:11;color:model.online&&model.paired?window.greenColor:window.mutedColor}}
                                    Label{text:{var r=revision;return summary(model.uuid)}
                                        font.pixelSize:12;color:window.mutedColor;elide:Text.ElideRight;Layout.fillWidth:true}
                                }
                                RhButton{text:"设置";onClicked:openProfile(model.uuid,model.name);Accessible.name:model.name+" 连接设置"}
                                RhButton{text:model.paired?"连接":"配对";primary:model.name.toLowerCase()==="winserver";enabled:model.online;onClicked:connectComputer(model.uuid);Accessible.name:(model.paired?"连接 ":"配对 ")+model.name}
                            }
                        }
                    }
                    Rectangle{Layout.fillWidth:true;height:1;color:window.lineColor;Layout.topMargin:14}
                    RowLayout{Layout.fillWidth:true;Label{text:"声音已关闭";font.pixelSize:12;color:window.mutedColor}Item{Layout.fillWidth:true}Label{text:"Ctrl + Alt + Shift + Q 断开";font.pixelSize:12;color:window.mutedColor}}
                }
                ColumnLayout {
                    visible:editing;Layout.fillWidth:true;spacing:14
                    Label{text:"画面尺寸";color:window.mutedColor;font.pixelSize:13}
                    RowLayout { Repeater{model:[{w:2520,h:1680},{w:1920,h:1080},{w:3840,h:2160}]
                        RhButton{text:modelData.w+" × "+modelData.h;chosen:profile.width===modelData.w&&profile.height===modelData.h;onClicked:{option("width",modelData.w);option("height",modelData.h);}}}}
                    Label{text:"帧率";color:window.mutedColor;font.pixelSize:13}
                    RowLayout{Repeater{model:[30,60];RhButton{text:modelData+" 帧";chosen:profile.fps===modelData;onClicked:option("fps",modelData)}}}
                    Label{text:"码率";color:window.mutedColor;font.pixelSize:13}
                    RowLayout{Repeater{model:[15,25,40];RhButton{text:modelData+" Mbps";chosen:profile.bitrateMbps===modelData;onClicked:option("bitrateMbps",modelData)}}}
                    Label{text:"编码偏好";color:window.mutedColor;font.pixelSize:13}
                    RowLayout{Repeater{model:["HEVC","H.264"];RhButton{text:modelData;chosen:profile.codec===modelData;onClicked:option("codec",modelData)}}}
                    CheckBox{text:"交换远程鼠标主副键";checked:profile.swapButtons===true;onToggled:option("swapButtons",checked)}
                    CheckBox{text:"显示性能统计";checked:profile.statistics===true;onToggled:option("statistics",checked)}
                    Label{text:"偏好仅影响客户端连接，声音保持关闭。";color:window.mutedColor;font.pixelSize:12}
                    RowLayout{Layout.fillWidth:true;Item{Layout.fillWidth:true}RhButton{text:"取消";onClicked:editing=false}RhButton{text:"保存";primary:true;onClicked:{if(RhProfiles.save(selectedUuid,profile)){revision++;editing=false;status="已保存 "+selectedName+" 的连接偏好";}else status="保存失败，请检查参数。";}}}
                }
                Label{Layout.fillWidth:true;Layout.topMargin:12;text:status;color:window.mutedColor;font.pixelSize:12;wrapMode:Text.Wrap;Accessible.role:Accessible.StaticText}
                Label{Layout.fillWidth:true;Layout.topMargin:12;text:"基于 Moonlight 开源核心 · GPL v3";font.pixelSize:11;color:window.mutedColor}
            }
        }
    }
    Dialog {
        id:addDialog;title:"添加电脑";anchors.centerIn:parent;modal:true;standardButtons:Dialog.Ok|Dialog.Cancel
        onAccepted:{if(address.text.trim().length){ComputerManager.addNewHost(address.text.trim(),false);status="正在添加 "+address.text.trim();}}
        TextField{id:address;placeholderText:"例如 192.168.0.104";width:300;selectByMouse:true;onAccepted:addDialog.accept()}
    }
    Dialog {
        id:pairDialog;title:"在 Sunshine 确认配对";anchors.centerIn:parent;modal:true;closePolicy:Popup.NoAutoClose;standardButtons:Dialog.Cancel
        ColumnLayout{spacing:15;Label{text:"请在主机的 Sunshine 管理界面输入：";color:window.inkColor}Label{id:pairCode;font.pixelSize:36;font.weight:Font.Medium;color:window.primaryColor;Layout.alignment:Qt.AlignHCenter}Label{text:"配对完成后窗口自动关闭。";font.pixelSize:12;color:window.mutedColor}}
    }
}
