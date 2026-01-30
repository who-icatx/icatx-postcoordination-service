package edu.stanford.protege.webprotege.postcoordinationservice.handlers;


import edu.stanford.protege.webprotege.ipc.EventHandler;
import edu.stanford.protege.webprotege.postcoordinationservice.events.ClassDeletedEvent;
import edu.stanford.protege.webprotege.postcoordinationservice.repositories.PostCoordinationRepository;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ClassDeletedEventHandler implements EventHandler<ClassDeletedEvent> {

    private final PostCoordinationRepository postCoordinationRepository;

    public ClassDeletedEventHandler(PostCoordinationRepository postCoordinationRepository) {
        this.postCoordinationRepository = postCoordinationRepository;
    }

    @NotNull
    @Override
    public String getChannelName() {
        return ClassDeletedEvent.CHANNEL;
    }

    @NotNull
    @Override
    public String getHandlerName() {
        return this.getClass().getName();
    }

    @Override
    public Class<ClassDeletedEvent> getEventClass() {
        return ClassDeletedEvent.class;
    }

    @Override
    public void handleEvent(ClassDeletedEvent event) {
        List<String> deletedIris = event.deletedIris()
                .stream()
                .map(iri -> iri.toString())
                .toList();
        postCoordinationRepository.deleteHistoriesForEntityIris(event.projectId(), deletedIris);
    }
}
