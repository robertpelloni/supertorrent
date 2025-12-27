#ifndef MEGATORRENT_I2P_SAM_H
#define MEGATORRENT_I2P_SAM_H

#include <QObject>
#include <QTcpSocket>
#include <QQueue>

namespace Megatorrent {

class SamSession : public QObject {
    Q_OBJECT

public:
    explicit SamSession(QObject *parent = nullptr);
    ~SamSession();

    void connectToSam(const QString &host = "127.0.0.1", quint16 port = 7656);
    void createSession(const QString &id);

    // Create a new socket connected to dest
    // Returns a socket in 'Connecting' state, performs SAM handshake internally?
    // Actually, qT doesn't allow "injecting" a handshake easily into a raw socket unless we wrap it.
    // We will provide a helper that sets up a socket, connects to SAM, and sends CONNECT.
    QTcpSocket* createStreamSocket(const QString &destination);

    bool isReady() const;
    QString myDestination() const;

signals:
    void ready();
    void errorOccurred(const QString &msg);

private slots:
    void onControlConnected();
    void onControlReadyRead();
    void onControlError(QAbstractSocket::SocketError error);

private:
    QTcpSocket *m_controlSocket;
    QString m_samHost;
    quint16 m_samPort;
    QString m_sessionId;
    QString m_myDestination;
    bool m_isReady;

    enum State {
        Disconnected,
        HandshakeSent,
        SessionCreateSent,
        Ready
    };
    State m_state;
};

}

#endif // MEGATORRENT_I2P_SAM_H
