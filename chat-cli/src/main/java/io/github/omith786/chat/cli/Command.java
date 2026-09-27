package io.github.omith786.chat.cli;

/** A line typed at the terminal client's prompt, parsed by {@link CommandParser}. */
public sealed interface Command {

    /** Plain text: send to the current room or conversation. */
    record Say(String text) implements Command {
    }

    /** {@code /join <room>}: join a room and make it current. */
    record Join(String room) implements Command {
    }

    /** {@code /leave [room]}: leave a room (the current one by default). */
    record Leave(String room) implements Command {
    }

    /** {@code /switch <room>} or {@code /switch @user}: change where plain text goes. */
    record Switch(String target) implements Command {
    }

    /** {@code /dm <user> <text>}: send one direct message without switching. */
    record Direct(String user, String text) implements Command {
    }

    /** {@code /rooms}: list rooms. */
    record Rooms() implements Command {
    }

    /** {@code /create <name>}: create a room. */
    record Create(String name) implements Command {
    }

    /** {@code /who [room]}: list online users, or a room's members. */
    record Who(String room) implements Command {
    }

    /** {@code /history [n]}: show recent messages for the current room or conversation. */
    record History(int count) implements Command {
    }

    /** {@code /help}. */
    record Help() implements Command {
    }

    /** {@code /quit} or {@code /exit}. */
    record Quit() implements Command {
    }

    /** Nothing to do (an empty line). */
    record Empty() implements Command {
    }

    /** The line could not be parsed; {@code message} says why. */
    record Invalid(String message) implements Command {
    }
}
