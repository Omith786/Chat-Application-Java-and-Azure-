package io.github.omith786.chat.server.message;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Persistence for {@link ChatMessage}. */
public interface MessageRepository extends JpaRepository<ChatMessage, Long> {

    /** The newest messages in a channel older than {@code beforeId}, newest first. */
    List<ChatMessage> findByChannelAndIdLessThanOrderByIdDesc(String channel, long beforeId, Limit limit);

    /** The latest message of every direct conversation {@code user} takes part in, newest first. */
    @Query("""
            select m from ChatMessage m
            where m.id in (
                select max(d.id) from ChatMessage d
                where d.recipient is not null and (d.sender = :user or d.recipient = :user)
                group by d.channel)
            order by m.id desc
            """)
    List<ChatMessage> findLatestDirectMessagesInvolving(@Param("user") String user);
}
