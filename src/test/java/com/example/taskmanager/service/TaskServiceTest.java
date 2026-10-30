package com.example.taskmanager.service;

import com.example.taskmanager.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskServiceTest {

    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService();
    }

    @Test
    void createAssignsIdTimestampAndPendingStatus() {
        Task created = service.create(new Task("  Write Dockerfile  ", "multi-stage"));

        assertThat(created.getId()).isEqualTo(1L);
        assertThat(created.getTitle()).isEqualTo("Write Dockerfile");
        assertThat(created.isCompleted()).isFalse();
        assertThat(created.getCreatedAt()).isNotNull();
    }

    @Test
    void findAllReturnsTasksInCreationOrder() {
        service.create(new Task("First", null));
        service.create(new Task("Second", null));

        assertThat(service.findAll())
                .extracting(Task::getTitle)
                .containsExactly("First", "Second");
    }

    @Test
    void toggleCompleteFlipsStatus() {
        Task t = service.create(new Task("Run tests", null));

        assertThat(service.toggleComplete(t.getId()).isCompleted()).isTrue();
        assertThat(service.toggleComplete(t.getId()).isCompleted()).isFalse();
    }

    @Test
    void updateChangesFields() {
        Task t = service.create(new Task("Old", null));
        Task changes = new Task("New", "desc");
        changes.setCompleted(true);

        Task updated = service.update(t.getId(), changes);

        assertThat(updated.getTitle()).isEqualTo("New");
        assertThat(updated.getDescription()).isEqualTo("desc");
        assertThat(updated.isCompleted()).isTrue();
    }

    @Test
    void deleteRemovesTask() {
        Task t = service.create(new Task("Temp", null));

        service.delete(t.getId());

        assertThat(service.findAll()).isEmpty();
    }

    @Test
    void missingTaskThrowsNotFound() {
        assertThatThrownBy(() -> service.findById(99L)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void countPendingIgnoresCompletedTasks() {
        service.create(new Task("A", null));
        Task b = service.create(new Task("B", null));
        service.toggleComplete(b.getId());

        assertThat(service.countPending()).isEqualTo(1);
    }
}
