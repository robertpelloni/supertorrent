#include "megatorrent_subscription.h"
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QFile>
#include <QDebug>
#include <QMutexLocker>

namespace Megatorrent {

SubscriptionManager::SubscriptionManager(DHTClient *dht, QObject *parent)
    : QObject(parent), m_dht(dht)
{
    m_pollTimer = new QTimer(this);
    connect(m_pollTimer, &QTimer::timeout, this, &SubscriptionManager::onPollTimer);
    connect(m_dht, &DHTClient::manifestFound, this, &SubscriptionManager::onManifestFound);
}

SubscriptionManager::~SubscriptionManager() {
    stopPolling();
}

void SubscriptionManager::addSubscription(const QString &label, const QByteArray &publicKey) {
    QMutexLocker locker(&m_mutex);
    if (m_subscriptions.contains(publicKey)) return;

    Subscription sub;
    sub.label = label;
    sub.publicKey = publicKey;
    sub.lastSequence = 0;
    sub.lastUpdated = QDateTime::currentDateTime();
    sub.lastChecked = QDateTime::currentDateTime(); // Now

    m_subscriptions.insert(publicKey, sub);

    // Immediate check
    m_dht->getManifest(publicKey);
}

void SubscriptionManager::removeSubscription(const QByteArray &publicKey) {
    QMutexLocker locker(&m_mutex);
    m_subscriptions.remove(publicKey);
}

QList<Subscription> SubscriptionManager::subscriptions() const {
    QMutexLocker locker(&m_mutex);
    return m_subscriptions.values();
}

void SubscriptionManager::startPolling(int intervalMs) {
    // Timer is thread-affine, usually safe if called from same thread.
    // If called from other thread, use meta-object system.
    // We assume same thread or careful usage.
    // Mutex not strictly needed for m_pollTimer pointer access if not changing it.
    QMetaObject::invokeMethod(m_pollTimer, "start", Qt::QueuedConnection, Q_ARG(int, intervalMs));
}

void SubscriptionManager::stopPolling() {
    QMetaObject::invokeMethod(m_pollTimer, "stop", Qt::QueuedConnection);
}

void SubscriptionManager::onPollTimer() {
    QMutexLocker locker(&m_mutex);
    for (auto it = m_subscriptions.begin(); it != m_subscriptions.end(); ++it) {
        m_dht->getManifest(it.key());
        it->lastChecked = QDateTime::currentDateTime();
    }
}

void SubscriptionManager::onManifestFound(const Manifest &manifest) {
    QMutexLocker locker(&m_mutex);
    if (!m_subscriptions.contains(manifest.publicKey)) return;

    Subscription &sub = m_subscriptions[manifest.publicKey];

    if (manifest.sequence > sub.lastSequence) {
        sub.lastSequence = manifest.sequence;
        sub.lastUpdated = QDateTime::currentDateTime();

        emit subscriptionUpdated(manifest.publicKey, manifest);
        qDebug() << "Megatorrent: Subscription updated:" << sub.label << "Seq:" << manifest.sequence;
    }
}

void SubscriptionManager::load(const QString &path) {
    QMutexLocker locker(&m_mutex);
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) return;

    QByteArray data = file.readAll();
    QJsonDocument doc = QJsonDocument::fromJson(data);
    if (!doc.isArray()) return;

    m_subscriptions.clear();
    QJsonArray arr = doc.array();
    for (const auto &val : arr) {
        QJsonObject obj = val.toObject();
        Subscription sub;
        sub.label = obj["label"].toString();
        sub.publicKey = QByteArray::fromHex(obj["pub"].toString().toLatin1());
        sub.lastSequence = obj["seq"].toVariant().toLongLong();
        sub.lastUpdated = QDateTime::fromString(obj["updated"].toString(), Qt::ISODate);
        sub.lastChecked = QDateTime::fromString(obj["checked"].toString(), Qt::ISODate);
        m_subscriptions.insert(sub.publicKey, sub);
    }
}

void SubscriptionManager::save(const QString &path) {
    QMutexLocker locker(&m_mutex);
    QJsonArray arr;
    for (const auto &sub : m_subscriptions) {
        QJsonObject obj;
        obj["label"] = sub.label;
        obj["pub"] = QString(sub.publicKey.toHex());
        obj["seq"] = sub.lastSequence;
        obj["updated"] = sub.lastUpdated.toString(Qt::ISODate);
        obj["checked"] = sub.lastChecked.toString(Qt::ISODate);
        arr.append(obj);
    }

    QJsonDocument doc(arr);
    QFile file(path);
    if (file.open(QIODevice::WriteOnly)) {
        file.write(doc.toJson());
    }
}

}
