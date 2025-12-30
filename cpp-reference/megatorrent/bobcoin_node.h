#ifndef MEGATORRENT_BOBCOIN_NODE_H
#define MEGATORRENT_BOBCOIN_NODE_H

#include <QObject>
#include <QVector>
#include <QVariantMap>

namespace Megatorrent {

struct DanceMove {
    QString arrow; // "UP", "DOWN", etc.
    qint64 timingMs;
    QString accuracy; // "PERFECT", "GOOD", etc.
};

class BobcoinNode : public QObject {
    Q_OBJECT

public:
    explicit BobcoinNode(QObject *parent = nullptr);
    ~BobcoinNode();

    void start();
    void stop();

    // Mining API
    bool submitDance(const QVector<DanceMove> &danceData);
    qint64 getBalance(const QString &publicKey) const;

signals:
    void blockMined(qint64 index, const QString &hash);
    void transactionReceived(const QString &txId);

private:
    bool m_isRunning;
    qint64 m_chainHeight;
};

}

#endif // MEGATORRENT_BOBCOIN_NODE_H
