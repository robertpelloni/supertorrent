#include "bobcoin_node.h"
#include <QDebug>
#include <QDateTime>
#include <QCryptographicHash>

namespace Megatorrent {

BobcoinNode::BobcoinNode(QObject *parent)
    : QObject(parent), m_isRunning(false), m_chainHeight(0)
{
}

BobcoinNode::~BobcoinNode() {
    stop();
}

void BobcoinNode::start() {
    if (m_isRunning) return;
    m_isRunning = true;
    qDebug() << "[BobcoinC++] Node Started. Mining Mode: Dance.";
}

void BobcoinNode::stop() {
    m_isRunning = false;
}

bool BobcoinNode::submitDance(const QVector<DanceMove> &danceData) {
    if (!m_isRunning) return false;

    // Simulate Proof of Dance Verification
    int score = 0;
    for (const auto &move : danceData) {
        if (move.accuracy == "PERFECT") score += 10;
        else if (move.accuracy == "GOOD") score += 5;
    }

    qDebug() << "[BobcoinC++] Verifying Dance. Score:" << score;

    if (score > 50) { // Difficulty Stub
        m_chainHeight++;
        QString hash = QString(QCryptographicHash::hash(QByteArray::number(m_chainHeight), QCryptographicHash::Sha256).toHex());
        qDebug() << "[BobcoinC++] Block Mined!" << hash;
        emit blockMined(m_chainHeight, hash);
        return true;
    }

    return false;
}

qint64 BobcoinNode::getBalance(const QString &publicKey) const {
    return 1000; // Stub
}

}
