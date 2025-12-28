#ifndef MEGATORRENT_SUBSCRIPTION_H
#define MEGATORRENT_SUBSCRIPTION_H

#include <QObject>
#include <QMap>
#include <QTimer>
#include <QDateTime>
#include <QRecursiveMutex>
#include "dht_client.h"

namespace Megatorrent {

struct Subscription {
    QString label;
    QByteArray publicKey;
    qint64 lastSequence;
    QDateTime lastUpdated;
    QDateTime lastChecked;
};

class SubscriptionManager : public QObject {
    Q_OBJECT

public:
    explicit SubscriptionManager(DHTClient *dht, QObject *parent = nullptr);
    ~SubscriptionManager();

    void addSubscription(const QString &label, const QByteArray &publicKey);
    void removeSubscription(const QByteArray &publicKey);
    QList<Subscription> subscriptions() const;

    void startPolling(int intervalMs = 600000); // Default 10 mins
    void stopPolling();

    // Persist to disk (JSON)
    void load(const QString &path);
    void save(const QString &path);

signals:
    void subscriptionUpdated(const QByteArray &publicKey, const Manifest &newManifest);

private slots:
    void onPollTimer();
    void onManifestFound(const Manifest &manifest);

private:
    DHTClient *m_dht;
    QMap<QByteArray, Subscription> m_subscriptions;
    QTimer *m_pollTimer;
    mutable QRecursiveMutex m_mutex;
};

}

#endif // MEGATORRENT_SUBSCRIPTION_H
