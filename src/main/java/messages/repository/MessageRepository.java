package messages.repository;

import messages.model.Message;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // один запрос с join - автор приходит сразу вместе с сообщением
    @Query("select m from Message m join fetch m.author order by m.id")
    List<Message> findAllWithAuthor();

    @EntityGraph(attributePaths = "author")
    Optional<Message> findWithAuthorById(Long id);
}
