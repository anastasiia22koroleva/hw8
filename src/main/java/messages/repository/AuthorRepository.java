package messages.repository;

import messages.model.Author;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    List<Author> findByUsernameIn(Collection<String> usernames);
}
