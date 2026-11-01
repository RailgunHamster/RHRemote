#pragma once
#include <QTcpServer>
#include <QTcpSocket>

namespace RhControlAccept {
inline QTcpSocket* next(QTcpServer& listener, int timeoutMs = 100) {
    // waitForNewConnection() waits on the listening socket even when Qt has
    // already accepted other clients. Drain that queue before waiting again.
    if (!listener.hasPendingConnections() && !listener.waitForNewConnection(timeoutMs))
        return nullptr;
    return listener.nextPendingConnection();
}
}
