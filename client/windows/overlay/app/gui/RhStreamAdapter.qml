import QtQuick 2.15
import QtQuick.Window 2.15
import ComputerManager 1.0
import RhProfiles 1.0

// The Rust shell owns navigation, progress and errors. Keep the streaming
// process free of a second controls tree and GUI gamepad initialization.
Window {
    id: adapter
    width: 1180
    height: 780
    visible: false
    title: "RHRemote-Moonlight"
    property var session: null

    Component.onCompleted: {
        RhProfiles.placeWindow(adapter)
        Qt.callLater(function() { launcher.execute(ComputerManager) })
    }

    Connections {
        target: launcher
        function onSearchingComputer() { RhProfiles.connectionStatus("connecting", "") }
        function onSearchingApp() { RhProfiles.connectionStatus("starting", "") }
        function onFailed(text) {
            RhProfiles.connectionStatus("error", text)
            Qt.quit()
        }
        function onAppQuitRequired(appName) {
            RhProfiles.connectionStatus("error", "主机正在运行 " + appName + "，请先结束该串流")
            Qt.quit()
        }
        function onSessionCreated(appName, session) {
            adapter.session = session
            // Leave the launcher handler before the blocking stream loop starts.
            Qt.callLater(function() { adapter.session.exec(adapter) })
        }
    }

    Connections {
        target: adapter.session
        function onDisplayLaunchError(text) { RhProfiles.connectionStatus("error", text) }
        function onDisplayLaunchWarning(text) { console.warn(text) }
        function onConnectionStarted() { adapter.visible = false }
        function onReadyForDeletion() {
            RhProfiles.connectionStatus("cleanup_complete", "")
            Qt.quit()
        }
    }
}
