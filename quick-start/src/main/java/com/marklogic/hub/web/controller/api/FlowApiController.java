package com.marklogic.hub.web.controller.api;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.http.concurrent.BasicFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.marklogic.hub.Mlcp;
import com.marklogic.hub.Mlcp.SourceOptions;
import com.marklogic.hub.config.EnvironmentConfiguration;
import com.marklogic.hub.flow.Flow;
import com.marklogic.hub.flow.FlowType;
import com.marklogic.hub.model.EntityModel;
import com.marklogic.hub.model.FlowModel;
import com.marklogic.hub.model.RunFlowModel;
import com.marklogic.hub.service.CancellableTask;
import com.marklogic.hub.service.FlowManagerService;
import com.marklogic.hub.service.TaskManagerService;
import com.marklogic.hub.web.controller.BaseController;
import com.marklogic.hub.web.form.FlowForm;
import com.marklogic.hub.web.form.LoginForm;

@RestController
@RequestMapping("/api/flows")
public class FlowApiController extends BaseController {

    private static final Logger LOGGER = LoggerFactory
            .getLogger(FlowApiController.class);

    @Autowired
    private EnvironmentConfiguration environmentConfiguration;

    @Autowired
    private FlowManagerService flowManagerService;

    @Autowired
    private TaskManagerService taskManagerService;

    @RequestMapping(value = "/flow", method = RequestMethod.GET)
    @ResponseBody
    public Flow getFlow(HttpServletRequest request) {
        final String entityName = request.getParameter("entityName");
        final String flowName = request.getParameter("flowName");
        return flowManagerService.getFlow(entityName, flowName);
    }

    @RequestMapping(method = RequestMethod.POST, consumes = { MediaType.APPLICATION_JSON_UTF8_VALUE }, produces = { MediaType.APPLICATION_JSON_UTF8_VALUE })
    @ResponseBody
    public EntityModel saveFlow(@RequestBody FlowForm flowForm,
            BindingResult bindingResult, HttpSession session) {
        LoginForm loginForm = (LoginForm) session.getAttribute("loginForm");
        EntityModel selectedEntity = loginForm.getSelectedEntity();
        List<FlowModel> flowList = this
                .getAllFlowsOfSelectedEntity(selectedEntity);

        flowForm.validate(flowList);

        FlowModel flowModel = flowManagerService.createFlow(
                flowForm.getEntityName(),
                flowForm.getFlowName(),
                flowForm.getFlowType(),
                flowForm.getPluginFormat(),
                flowForm.getDataFormat());

        if (flowForm.getFlowType().equals(FlowType.CONFORMANCE)) {
            selectedEntity.getConformFlows().add(flowModel);
        } else {
            selectedEntity.getInputFlows().add(flowModel);
        }
        return selectedEntity;
    }

    private List<FlowModel> getAllFlowsOfSelectedEntity(
            EntityModel selectedEntity) {
        List<FlowModel> flowList = new ArrayList<>();
        flowList.addAll(selectedEntity.getInputFlows());
        flowList.addAll(selectedEntity.getConformFlows());
        return flowList;
    }

    @RequestMapping(value = "/install", method = RequestMethod.POST)
    public void installFlow(HttpServletRequest request) {
        final String entityName = request.getParameter("entityName");
        final String flowName = request.getParameter("flowName");
        final Flow flow = flowManagerService.getFlow(entityName, flowName);
        flowManagerService.installFlow(flow);
    }

    @RequestMapping(value = "/uninstall", method = RequestMethod.POST)
    public void uninstallFlow(HttpServletRequest request) {
        final String flowName = request.getParameter("flowName");
        flowManagerService.uninstallFlow(flowName);
    }

    @RequestMapping(value = "/test", method = RequestMethod.POST)
    public BigInteger testFlow(HttpServletRequest request) {
        CancellableTask task = new CancellableTask() {

            private JobExecution jobExecution;

            @Override
            public void cancel(BasicFuture<?> resultFuture) {
                if (jobExecution != null) {
                    jobExecution.stop();
                }
            }

            @Override
            public void run(BasicFuture<?> resultFuture) {
        final String entityName = request.getParameter("entityName");
        final String flowName = request.getParameter("flowName");
        final Flow flow = flowManagerService.getFlow(entityName, flowName);
                this.jobExecution = flowManagerService.testFlow(flow);
    }
        };

        return taskManagerService.addTask(task);
    }

    @RequestMapping(value = "/run", method = RequestMethod.POST)
    public BigInteger runFlow(@RequestBody RunFlowModel runFlow) {
        CancellableTask task = new CancellableTask() {

            private JobExecution jobExecution;

            @Override
            public void cancel(BasicFuture<?> resultFuture) {
                if (jobExecution != null) {
                    jobExecution.stop();
                }
            }

            @Override
            public void run(BasicFuture<?> resultFuture) {
        final Flow flow = flowManagerService.getFlow(runFlow.getEntityName(),
                runFlow.getFlowName());
        // TODO update and move BATCH SIZE TO a constant or config - confirm
        // desired behavior
                this.jobExecution = flowManagerService.runFlow(flow, 100, new JobExecutionListener() {

                    @Override
                    public void beforeJob(JobExecution jobExecution) {
    }

                    @Override
                    public void afterJob(JobExecution jobExecution) {
                        resultFuture.completed(null);
                    }
                });
            }
        };

        return taskManagerService.addTask(task);
    }

    @RequestMapping(value="/run/input", method = RequestMethod.POST)
    public BigInteger runInputFlow(@RequestBody RunFlowModel runFlow) {
        CancellableTask task = new CancellableTask() {

            @Override
            public void cancel(BasicFuture<?> resultFuture) {
                // TODO: stop MLCP. We don't have a way to do this yet.
            }

            @Override
            public void run(BasicFuture<?> resultFuture) {
        try {
            Mlcp mlcp = new Mlcp(
                            environmentConfiguration.getMLHost()
                            ,Integer.parseInt(environmentConfiguration.getMLStagingRestPort())
                            ,environmentConfiguration.getMLUsername()
                            ,environmentConfiguration.getMLPassword()
                        );

            SourceOptions sourceOptions = new SourceOptions(
                    runFlow.getEntityName(), runFlow.getFlowName(),
                    FlowType.INPUT.toString());
            mlcp.addSourceDirectory(runFlow.getInputPath(), sourceOptions);
            mlcp.loadContent();

                    resultFuture.completed(null);
        }
        catch (IOException e) {
            LOGGER.error("Error encountered while trying to run flow:  "
                    + runFlow.getEntityName() + " > " + runFlow.getFlowName(),
                    e);
        }
    }
        };
        return taskManagerService.addTask(task);
    }

    @RequestMapping(value = "/runInParallel", method = RequestMethod.POST)
    public void runFlowsInParallel(HttpServletRequest request) {
        final String entityName = request.getParameter("entityName");
        String[] flowNames = request.getParameterValues("flowName");
        List<Flow> flows = new ArrayList<Flow>();
        for (String flowName : flowNames) {
            final Flow flow = flowManagerService.getFlow(entityName, flowName);
            flows.add(flow);
        }
        flowManagerService.runFlowsInParallel(flows.toArray(new Flow[flows
                .size()]));
    }

}