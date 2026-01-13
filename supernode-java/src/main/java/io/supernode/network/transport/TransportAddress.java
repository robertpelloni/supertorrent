package io.supernode.network.transport;

public record TransportAddress(
    TransportType type,
    String host,
    int port,
    String raw
) {
    public static TransportAddress clearnet(String host, int port) {
        return new TransportAddress(TransportType.CLEARNET, host, port, host + ":" + port);
    }
    
    public static TransportAddress onion(String onionAddress, int port) {
        return new TransportAddress(TransportType.TOR, onionAddress, port, onionAddress + ":" + port);
    }
    
    public static TransportAddress i2p(String i2pAddress, int port) {
        return new TransportAddress(TransportType.I2P, i2pAddress, port, i2pAddress + ":" + port);
    }
    
    public static TransportAddress hyphanet(String key) {
        return new TransportAddress(TransportType.HYPHANET, key, 0, key);
    }
    
    public static TransportAddress zeronet(String siteAddress) {
        return new TransportAddress(TransportType.ZERONET, siteAddress, 0, siteAddress);
    }
    
    public static TransportAddress ipfs(String peerId) {
        return new TransportAddress(TransportType.IPFS, peerId, 0, peerId);
    }
    
    public String address() {
        return raw;
    }
    
    @Override
    public String toString() {
        return raw;
    }
}
