package com.lts.jobtracker.complete.chain;

import com.lts.core.commons.utils.CollectionUtils;
import com.lts.core.commons.utils.StringUtils;
import com.lts.core.constant.Constants;
import com.lts.core.domain.Action;
import com.lts.core.domain.Job;
import com.lts.core.domain.TaskTrackerJobResult;
import com.lts.core.protocol.command.JobCompletedRequest;
import com.lts.core.support.JobDomainConverter;
import com.lts.jobtracker.complete.JobCompleteHandler;
import com.lts.jobtracker.complete.JobFinishHandler;
import com.lts.jobtracker.complete.JobRetryHandler;
import com.lts.jobtracker.domain.JobTrackerAppContext;
import com.lts.jobtracker.support.ClientNotifier;
import com.lts.jobtracker.support.ClientNotifyHandler;
import com.lts.queue.domain.JobFeedbackPo;
import com.lts.remoting.protocol.RemotingCommand;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务完成 China
 *
 * @author Robert HG (254963746@qq.com) on 11/11/15.
 */
public class JobProcessChain implements JobCompletedChain {

    private ClientNotifier clientNotifier;
    private final JobCompleteHandler retryHandler;
    private final JobCompleteHandler jobFinishHandler;
    // 任务的最大重试次数
    private final Integer globalMaxRetryTimes;

    public JobProcessChain(final JobTrackerAppContext appContext) {
        this.retryHandler = new JobRetryHandler(appContext);
        this.jobFinishHandler = new JobFinishHandler(appContext);

        this.globalMaxRetryTimes = appContext.getConfig().getParameter(Constants.JOB_MAX_RETRY_TIMES,
                Constants.DEFAULT_JOB_MAX_RETRY_TIMES);

        this.clientNotifier = new ClientNotifier(appContext, new ClientNotifyHandler<TaskTrackerJobResult>() {
            @Override
            public void handleSuccess(List<TaskTrackerJobResult> results) {
                jobFinishHandler.onComplete(results);
            }

            @Override
            public void handleFailed(List<TaskTrackerJobResult> results) {
                if (CollectionUtils.isNotEmpty(results)) {
                    List<JobFeedbackPo> jobFeedbackPos =
                            new ArrayList<JobFeedbackPo>(results.size());

                    for (TaskTrackerJobResult result : results) {
                        JobFeedbackPo jobFeedbackPo = JobDomainConverter.convert(result);
                        jobFeedbackPos.add(jobFeedbackPo);
                    }
                    // 2. 失败的存储在反馈队列
                    appContext.getJobFeedbackQueue().add(jobFeedbackPos);
                    // 3. 完成任务 
                    jobFinishHandler.onComplete(results);
                }
            }
        });
    }

    @Override
    public RemotingCommand doChain(JobCompletedRequest request) {

        List<TaskTrackerJobResult> results = request.getTaskTrackerJobResults();

        if (CollectionUtils.sizeOf(results) == 1) {
            singleResultsProcess(results);
        } else {
            multiResultsProcess(results);
        }

        return null;
    }

    private void singleResultsProcess(List<TaskTrackerJobResult> results) {
        TaskTrackerJobResult result = results.get(0);

        if (!needRetry(result)) {
            // 这种情况下，如果要反馈客户端的，直接反馈客户端，不进行重试
            if (isNeedFeedback(result.getJobWrapper().getJob())) {
                clientNotifier.send(results);
            } else {
                jobFinishHandler.onComplete(results);
            }
        } else {
            // 需要retry
            retryHandler.onComplete(results);
        }
    }

    /**
     * 判断任务是否需要加入重试队列
     */
    private boolean needRetry(TaskTrackerJobResult result) {
        // 判断类型
        if(!(Action.EXECUTE_LATER.equals(result.getAction())
                || Action.EXECUTE_EXCEPTION.equals(result.getAction()))){
            return false;
        }

        // 判断重试次数
        Job job = result.getJobWrapper().getJob();
        Integer retryTimes = job.getRetryTimes();
        int jobMaxRetryTimes = job.getMaxRetryTimes();
        return !(retryTimes >= globalMaxRetryTimes || retryTimes >= jobMaxRetryTimes);
    }

    /**
     * 这里情况一般是发送失败，重新发送的
     */
    private void multiResultsProcess(List<TaskTrackerJobResult> results) {

        List<TaskTrackerJobResult> retryResults = null;

        // 过滤出来需要通知客户端的
        List<TaskTrackerJobResult> feedbackResults = null;
        // 不需要反馈的
        List<TaskTrackerJobResult> finishResults = null;

        for (TaskTrackerJobResult result : results) {

            if (needRetry(result)) {
                // 需要加入到重试队列的
                if (retryResults == null) {
                    retryResults = new ArrayList<TaskTrackerJobResult>();
                }
                retryResults.add(result);
            } else if (isNeedFeedback(result.getJobWrapper().getJob())) {
                // 需要反馈给客户端
                if (feedbackResults == null) {
                    feedbackResults = new ArrayList<TaskTrackerJobResult>();
                }
                feedbackResults.add(result);
            } else {
                // 不用反馈客户端，也不用重试，直接完成处理
                if (finishResults == null) {
                    finishResults = new ArrayList<TaskTrackerJobResult>();
                }
                finishResults.add(result);
            }
        }

        // 通知客户端
        clientNotifier.send(feedbackResults);

        // 完成任务
        jobFinishHandler.onComplete(finishResults);

        // 将任务加入到重试队列
        retryHandler.onComplete(retryResults);
    }

    private boolean isNeedFeedback(Job job) {
        if (job == null) {
            return false;
        }
        // 容错,如果没有提交节点组,那么不反馈
        return !StringUtils.isEmpty(job.getSubmitNodeGroup()) && job.isNeedFeedback();
    }

}
