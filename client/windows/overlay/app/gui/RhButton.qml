import QtQuick 2.15
import QtQuick.Controls 2.15
Button {
    id: control
    property bool primary: false
    property bool chosen: false
    implicitHeight: 44
    implicitWidth: Math.max(44,contentItem.implicitWidth+34)
    hoverEnabled: true
    font.pixelSize: 14
    font.weight: Font.Medium
    background: Rectangle {
        radius: 22
        color: !control.enabled ? window.lineColor : control.primary ? (control.hovered?window.primaryHover:window.primaryColor) : control.chosen||control.hovered?window.blueSoft:window.surfaceColor
        border.width: control.primary?0:1
        border.color: control.chosen?window.blueLine:window.lineColor
    }
    contentItem: Text {
        text: control.text;font: control.font
        color: control.primary?"white":control.chosen?window.primaryText:window.inkColor
        horizontalAlignment: Text.AlignHCenter;verticalAlignment: Text.AlignVCenter
    }
}
