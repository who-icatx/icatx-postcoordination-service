package edu.stanford.protege.webprotege.postcoordinationservice.config;


import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import edu.stanford.protege.webprotege.common.UserId;
import edu.stanford.protege.webprotege.ipc.CommandExecutor;
import edu.stanford.protege.webprotege.ipc.impl.CommandExecutorImpl;
import edu.stanford.protege.webprotege.postcoordinationservice.*;
import edu.stanford.protege.webprotege.jackson.WebProtegeJacksonApplication;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.CheckNonExistentIrisAction;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.CheckNonExistentIrisResult;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetIcatxEntityTypeRequest;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.GetIcatxEntityTypeResponse;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.LinearizationDefinitionRequest;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.LinearizationDefinitionResponse;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.ValidateAxisBelongsToHierarchyAction;
import edu.stanford.protege.webprotege.postcoordinationservice.dto.ValidateAxisBelongsToHierarchyResult;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import uk.ac.manchester.cs.owl.owlapi.OWLDataFactoryImpl;

import java.util.List;
import java.util.concurrent.locks.*;

@Configuration
public class ApplicationBeans {


    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new WebProtegeJacksonApplication().objectMapper(new OWLDataFactoryImpl());
        SimpleModule module = new SimpleModule("PostcoordinationModule");
        module.addDeserializer(IRI.class, new IriDeserializer());
        module.addSerializer(IRI.class, new IriSerializer());
        module.addDeserializer(UserId.class, new UserIdDeserializer());
        module.addSerializer(UserId.class, new UserIdSerializer());
        objectMapper.registerModule(module);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        return objectMapper;
    }

    @Bean
    public MongoCustomConversions customConversions(ObjectMapper objectMapper) {
        return new MongoCustomConversions(
                List.of(
                        new PostcoordinationEventReadingConverter(objectMapper),
                        new PostcoordinationEventWritingConverter(objectMapper),
                        new PostCoordinationCustomScalesReadingConverter(objectMapper),
                        new PostCoordinationCustomScalesWritingConverter(objectMapper)
                )
        );
    }


    @Bean
    public ReadWriteLock readWriteLock() {
        return new ReentrantReadWriteLock(true);
    }

    @Bean
    CommandExecutor<LinearizationDefinitionRequest, LinearizationDefinitionResponse> executorForLinearizationDefinitions() {
        return new CommandExecutorImpl<>(LinearizationDefinitionResponse.class);
    }


    @Bean
    CommandExecutor<GetIcatxEntityTypeRequest, GetIcatxEntityTypeResponse> executorForPrepareBackupFilesForUse() {
        return new CommandExecutorImpl<>(GetIcatxEntityTypeResponse.class);
    }

    @Bean
    CommandExecutor<CheckNonExistentIrisAction, CheckNonExistentIrisResult> executorForCheckNonExistentIris() {
        return new CommandExecutorImpl<>(CheckNonExistentIrisResult.class);
    }

    @Bean
    CommandExecutor<ValidateAxisBelongsToHierarchyAction, ValidateAxisBelongsToHierarchyResult> executorForValidateAxisBelongsToHierarchy() {
        return new CommandExecutorImpl<>(ValidateAxisBelongsToHierarchyResult.class);
    }

}
