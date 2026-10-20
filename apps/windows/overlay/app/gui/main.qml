import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Controls.Material 2.15
import QtQuick.Layouts 1.15
import ComputerManager 1.0
import StreamingPreferences 1.0
import SdlGamepadKeyNavigation 1.0
import RhProfiles 1.0

ApplicationWindow {
    id: window
    width: 1180;height: 780;minimumWidth: 720;minimumHeight: 560
    title: "RHRemote"
    property bool dark: false
    property bool pollingActive: false
    property bool clearOnBack: false
    readonly property color pageColor: dark?"#151b29":"#f4f7fc"
    readonly property color surfaceColor: dark?"#1b2232":"#ffffff"
    readonly property color sideColor: dark?"#171e2d":"#f4f7fc"
    readonly property color inkColor: dark?"#e1e8f6":"#34415b"
    readonly property color mutedColor: dark?"#a4b1c8":"#69778e"
    readonly property color lineColor: dark?"#303c53":"#e5eaf4"
    readonly property color primaryColor: dark?"#6f8af1":"#5274e8"
    readonly property color primaryHover: dark?"#8199f5":"#4666d6"
    readonly property color primaryText: dark?"#afc0ff":"#4666d6"
    readonly property color blueSoft: dark?"#253452":"#edf2ff"
    readonly property color blueLine: dark?"#485d8e":"#c7d4f6"
    readonly property color purpleColor: dark?"#ba9aef":"#8b5be2"
    readonly property color purpleSoft: dark?"#332845":"#f4efff"
    readonly property color greenColor: dark?"#8bc7b1":"#2e795f"
    color: pageColor;font.family: "Microsoft YaHei UI";font.pixelSize: 14
    Material.theme: dark?Material.Dark:Material.Light
    Material.primary: primaryColor;Material.accent: primaryColor
    Material.background: surfaceColor;Material.foreground: inkColor
    function goBack(){if(stackView.depth>1)stackView.pop();}
    function navigateTo(url,type){stackView.push(url);}
    function qmltypeof(obj,name){return obj&&obj.toString().indexOf(name)===0;}
    Component.onCompleted: {show();RhProfiles.placeWindow(window);ComputerManager.startPolling();pollingActive=true;SdlGamepadKeyNavigation.enable();}
    onVisibleChanged: {
        if(!visible&&pollingActive){ComputerManager.stopPollingAsync();pollingActive=false;}
        else if(visible&&!pollingActive){ComputerManager.startPolling();pollingActive=true;}
    }
    header: Rectangle {
        id: toolBar;height: 52;color: window.surfaceColor
        Rectangle{anchors.bottom: parent.bottom;width: parent.width;height: 1;color: window.lineColor}
        RowLayout {
            anchors.fill: parent;anchors.leftMargin: 22;anchors.rightMargin: 22;spacing: 12
            Label{text:"RHRemote";font.weight:Font.Medium;color:window.inkColor}
            Label{text:"0.1 · v2";font.pixelSize:11;color:window.mutedColor}
            Item{Layout.fillWidth:true}
            RhButton{text:window.dark?"浅色":"深色";onClicked:window.dark=!window.dark}
            RhButton{text:stackView.depth>1?"返回设备":"连接偏好";onClicked:{if(stackView.depth>1)goBack();else if(stackView.currentItem.openDefaultProfile)stackView.currentItem.openDefaultProfile();}}
        }
    }
    StackView {
        id: stackView;anchors.fill: parent;initialItem: initialView;focus: true
        Keys.onEscapePressed: {if(depth>1)window.goBack();}
        Keys.onBackPressed: {if(depth>1)window.goBack();}
    }
    ErrorMessageDialog { id: streamSegueErrorDialog
        property bool quitAfter:false
        onOpened:{if(RhProfiles.apiControlled())Qt.quit();}
        onClosed:{if(quitAfter)Qt.quit();text="";}
    }
}
