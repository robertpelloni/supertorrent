#include "i2p_sam.h"
#include <QDebug>

namespace Megatorrent {

SamSession::SamSession(QObject *parent)
    : QObject(parent), m_controlSocket(new QTcpSocket(this)), m_isReady(false), m_state(Disconnected)
{
    connect(m_controlSocket, &QTcpSocket::connected, this, &SamSession::onControlConnected);
    connect(m_controlSocket, &QTcpSocket::readyRead, this, &SamSession::onControlReadyRead);
    connect(m_controlSocket, QOverload<QAbstractSocket::SocketError>::of(&QTcpSocket::error),
            this, &SamSession::onControlError);
}

SamSession::~SamSession() {
    if (m_controlSocket->isOpen()) m_controlSocket->close();
}

void SamSession::connectToSam(const QString &host, quint16 port) {
    m_samHost = host;
    m_samPort = port;
    m_controlSocket->connectToHost(host, port);
}

void SamSession::createSession(const QString &id) {
    m_sessionId = id;
    // Triggered after handshake
}

bool SamSession::isReady() const {
    return m_isReady;
}

QString SamSession::myDestination() const {
    return m_myDestination;
}

void SamSession::onControlConnected() {
    // Send Hello
    m_controlSocket->write("HELLO VERSION MIN=3.0 MAX=3.1\n");
    m_state = HandshakeSent;
}

void SamSession::onControlReadyRead() {
    while (m_controlSocket->canReadLine()) {
        QByteArray line = m_controlSocket->readLine().trimmed();
        qDebug() << "SAM RX:" << line;

        if (m_state == HandshakeSent) {
            if (line.contains("RESULT=OK")) {
                // Handshake success, create session
                QString cmd = QString("SESSION CREATE STYLE=STREAM ID=%1 DESTINATION=TRANSIENT\n").arg(m_sessionId);
                m_controlSocket->write(cmd.toUtf8());
                m_state = SessionCreateSent;
            } else {
                emit errorOccurred("SAM Handshake Failed: " + line);
                m_controlSocket->close();
            }
        } else if (m_state == SessionCreateSent) {
            if (line.contains("RESULT=OK")) {
                // Extract Destination
                // DESTINATION=...
                int idx = line.indexOf("DESTINATION=");
                if (idx != -1) {
                    m_myDestination = line.mid(idx + 12);
                }
                m_isReady = true;
                m_state = Ready;
                emit ready();
            } else {
                emit errorOccurred("SAM Session Create Failed: " + line);
            }
        }
    }
}

void SamSession::onControlError(QAbstractSocket::SocketError error) {
    Q_UNUSED(error);
    emit errorOccurred(m_controlSocket->errorString());
}

QTcpSocket* SamSession::createStreamSocket(const QString &destination) {
    if (!m_isReady) return nullptr;

    QTcpSocket *socket = new QTcpSocket(this); // Parented to this for memory mgmt? Or transfer ownership?
    // Usually caller takes ownership.
    socket->setParent(nullptr);

    socket->connectToHost(m_samHost, m_samPort);

    // We need to perform the STREAM CONNECT logic.
    // Since QTcpSocket is async, we can't write immediately unless we wait for connected.
    // We can use a helper object or just queue the write.
    // For this reference, we'll assume the caller handles the "Wait for connect -> Send Command -> Wait for Result -> Handover" flow.
    // Or we can subclass QTcpSocket?
    // "SamStreamSocket : public QTcpSocket"

    // Stub: Return raw socket. The caller must write:
    // STREAM CONNECT ID=$id DESTINATION=$dest\n
    // And wait for RESULT=OK before treating it as a stream.

    return socket;
}

}
