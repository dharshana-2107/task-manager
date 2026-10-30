package com.example.taskmanager.service;

import com.example.taskmanager.model.Task;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory task store. Data resets when the app restarts, which is fine
 * for a CI/CD demo: the pipeline is the subject, not the persistence layer.
 */
@Service
public class TaskService {

    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    public List<Task> findAll() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(Task::getId))
                .toList();
    }

    public Task findById(Long id) {
        Task task = tasks.get(id);
        if (task == null) {
            throw new TaskNotFoundException(id);
        }
        return task;
    }

    public Task create(Task input) {
        Task task = new Task(input.getTitle().trim(), input.getDescription());
        task.setId(idSequence.getAndIncrement());
        task.setCompleted(false);
        task.setCreatedAt(Instant.now());
        tasks.put(task.getId(), task);
        return task;
    }

    public Task update(Long id, Task input) {
        Task existing = findById(id);
        existing.setTitle(input.getTitle().trim());
        existing.setDescription(input.getDescription());
        existing.setCompleted(input.isCompleted());
        return existing;
    }

    public Task toggleComplete(Long id) {
        Task existing = findById(id);
        existing.setCompleted(!existing.isCompleted());
        return existing;
    }

    public void delete(Long id) {
        if (tasks.remove(id) == null) {
            throw new TaskNotFoundException(id);
        }
    }

    public long countPending() {
        return tasks.values().stream().filter(t -> !t.isCompleted()).count();
    }
}
