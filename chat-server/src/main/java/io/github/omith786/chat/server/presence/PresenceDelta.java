package io.github.omith786.chat.server.presence;

/** A change in cluster-wide presence that clients should be told about. */
public sealed interface PresenceDelta {

    /** The user now has at least one connection somewhere in the cluster. */
    record UserOnline(String user) implements PresenceDelta {
    }

    /** The user's last connection in the cluster closed. */
    record UserOffline(String user) implements PresenceDelta {
    }

    /** The user is now in the room on at least one instance. */
    record RoomJoined(String room, String user) implements PresenceDelta {
    }

    /** The user is no longer in the room on any instance. */
    record RoomLeft(String room, String user) implements PresenceDelta {
    }
}
