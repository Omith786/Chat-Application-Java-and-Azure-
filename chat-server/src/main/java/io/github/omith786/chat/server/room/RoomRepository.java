package io.github.omith786.chat.server.room;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Persistence for {@link Room}. */
public interface RoomRepository extends JpaRepository<Room, String> {

    /** All rooms, oldest first, so the seeded rooms stay at the top of the list. */
    List<Room> findAllByOrderByCreatedAtAscIdAsc();
}
