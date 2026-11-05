package messages;

import jakarta.persistence.EntityManagerFactory;
import messages.service.MessageDto;
import messages.service.MessageService;
import messages.service.NewMessage;
import org.hibernate.LazyInitializationException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class MessageServiceTest {

    private static final int MESSAGES = 30;
    private static final int AUTHORS = 5;

    @Autowired
    private MessageService messageService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        List<NewMessage> data = new ArrayList<>();
        for (int i = 1; i <= MESSAGES; i++) {
            data.add(new NewMessage("user" + (i % AUTHORS + 1), "сообщение " + i));
        }
        messageService.saveAll(data);

        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void nPlusOneMakesQueryForEveryAuthor() {
        List<MessageDto> messages = messageService.getMessagesNPlusOne();

        assertEquals(MESSAGES, messages.size());
        assertEquals(1 + AUTHORS, statistics.getPrepareStatementCount());
    }

    @Test
    void optimizedMakesSingleQuery() {
        List<MessageDto> messages = messageService.getMessagesOptimized();

        assertEquals(MESSAGES, messages.size());
        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void bothMethodsReturnSameData() {
        assertEquals(messageService.getMessagesNPlusOne(), messageService.getMessagesOptimized());
    }

    @Test
    void lazyAuthorOutsideTransactionFails() {
        Long id = messageService.getMessagesOptimized().get(0).id();

        assertThrows(LazyInitializationException.class, () -> messageService.getMessageToFail(id));
        assertEquals("user2", messageService.getMessage(id).author());
    }
}
