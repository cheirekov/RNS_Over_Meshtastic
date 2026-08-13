package bg.reticulum.meshtastic.bridge;

interface RadioTransport extends AutoCloseable {
    interface Listener {
        void onRadioState(boolean connected, String detail);
        void onLocalNode(long nodeNumber);
        void onQueueStatus(int free, int max, int result);
        void onPacket(ProtoCodec.RadioPacket packet);
    }

    void start(Listener listener);
    boolean isReady();
    void send(byte[] payload, long destination) throws Exception;
    @Override void close();
}
