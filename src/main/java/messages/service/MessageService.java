package messages.service;

import messages.model.Author;
import messages.model.Message;
import messages.repository.AuthorRepository;
import messages.repository.MessageRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final AuthorRepository authorRepository;

    public MessageService(MessageRepository messageRepository, AuthorRepository authorRepository) {
        this.messageRepository = messageRepository;
        this.authorRepository = authorRepository;
    }

    @Transactional
    public int saveAll(List<NewMessage> newMessages) {
        Set<String> usernames = new LinkedHashSet<>();
        for (NewMessage m : newMessages) {
            if (m.author() == null || m.author().isBlank() || m.text() == null || m.text().isBlank()) {
                throw new IllegalArgumentException("У сообщения должны быть author и text");
            }
            usernames.add(m.author().trim());
        }

        Map<String, Author> authors = new HashMap<>();
        for (Author a : authorRepository.findByUsernameIn(usernames)) {
            authors.put(a.getUsername(), a);
        }
        for (String username : usernames) {
            authors.computeIfAbsent(username, name -> authorRepository.save(new Author(name)));
        }

        List<Message> messages = new ArrayList<>(newMessages.size());
        for (NewMessage m : newMessages) {
            messages.add(new Message(m.text(), authors.get(m.author().trim())));
        }
        messageRepository.saveAll(messages);
        return messages.size();
    }

    /**
     * Проблема N+1: первый запрос достаёт сообщения, а автор у каждого сообщения - прокси.
     * Обращение к getUsername() у прокси делает отдельный select для каждого нового автора.
     */
    @Transactional(readOnly = true)
    public List<MessageDto> getMessagesNPlusOne() {
        List<Message> messages = messageRepository.findAll(Sort.by("id"));
        List<MessageDto> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            result.add(MessageDto.from(message));
        }
        return result;
    }

    /**
     * То же самое, но авторы подгружаются в том же запросе через join fetch.
     */
    @Transactional(readOnly = true)
    public List<MessageDto> getMessagesOptimized() {
        List<Message> messages = messageRepository.findAllWithAuthor();
        List<MessageDto> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            result.add(MessageDto.from(message));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public MessageDto getMessage(Long id) {
        return messageRepository.findWithAuthorById(id)
                .map(MessageDto::from)
                .orElseThrow(() -> new MessageNotFoundException(id));
    }

    /**
     * Метод без @Transactional: findById открывает и закрывает транзакцию внутри репозитория,
     * поэтому к моменту вызова MessageDto.from() сессии уже нет, а author остаётся
     * неинициализированным прокси -> LazyInitializationException.
     */
    public MessageDto getMessageToFail(Long id) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new MessageNotFoundException(id));
        return MessageDto.from(message);
    }

    public long count() {
        return messageRepository.count();
    }
}
