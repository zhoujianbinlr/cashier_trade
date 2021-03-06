/**
 * <html>
 * <body>
 *  <P> Copyright 2014 广东天泽阳光康众医疗投资管理有限公司. 粤ICP备09007530号-15</p>
 *  <p> All rights reserved.</p>
 *  <p> Created on 2017年10月19日</p>
 *  <p> Created by hqy</p>
 *  </body>
 * </html>
 */
package com.sunshine.task.biz.collector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import com.alibaba.fastjson.JSON;
import com.sunshine.common.GlobalConstant;
import com.sunshine.framework.common.spring.ext.SpringContextHolder;
import com.sunshine.framework.common.threadpool.SimpleThreadFactory;
import com.sunshine.platform.application.entity.MerchantApplication;
import com.sunshine.platform.application.service.MerchantApplicationService;
import com.sunshine.platform.merchant.entity.Merchant;
import com.sunshine.platform.merchant.service.MerchantService;
import com.sunshine.task.biz.TaskConstant;
import com.sunshine.task.biz.callable.HandleRefundExceptionCollectorCall;
import com.sunshine.task.biz.vo.TaskParamsVo;
import com.sunshine.task.biz.vo.TaskResultInfo;

/**
 * @Project: cashier_trade 
 * @Package: com.sunshine.task.biz.collector
 * @ClassName: HandleRefundExceptionCollector
 * @Description: <p>退费异常订单处理任务</p>
 * @JDK version used: 
 * @Author: 百部
 * @Create Date: 2017年10月19日
 * @modify By:
 * @modify Date:
 * @Why&What is modify:
 * @Version: 1.0
 */
public class HandleRefundExceptionCollector {
	public static Logger logger = LoggerFactory.getLogger(HandleOrderExceptionCollector.class);

	private MerchantService merchantService = SpringContextHolder.getBean(MerchantService.class);

	private MerchantApplicationService maService = SpringContextHolder.getBean(MerchantApplicationService.class);

	public void start() {
		// 根据采集的机器配置得出默认的线程数
		int threadNum = TaskConstant.threadNum;
		logger.info("定任务执行,threadNum:{}", threadNum);

		//获取所有的商户
		//获取所有的商户
		List<Merchant> merchants = merchantService.getAllMerchant();

		List<MerchantApplication> applications = new ArrayList<MerchantApplication>();
		if (!CollectionUtils.isEmpty(merchants)) {
			for (Merchant merchant : merchants) {
				if (GlobalConstant.YES == merchant.getStatus()) {
					List<MerchantApplication> list = maService.findApplicationByMechNo(merchant.getMerchantNo());
					if (!CollectionUtils.isEmpty(list)) {
						applications.addAll(list);
					}
				}
			}
			logger.info("需处理退费异常订单应用:{}", JSON.toJSONString(applications));
		} else {
			logger.info("需处理退费异常订单应用为空");
		}
		if (applications.size() < threadNum) {
			threadNum = applications.size();
		}

		if (threadNum > 0) {
			// 设置线程池的数量
			ExecutorService collectExec = Executors.newFixedThreadPool(threadNum, new SimpleThreadFactory("refundOrderExce"));
			// map中的key为2种
			List<FutureTask<TaskResultInfo>> taskList = new ArrayList<FutureTask<TaskResultInfo>>();

			for (MerchantApplication application : applications) {
				TaskParamsVo paramVo = new TaskParamsVo();
				paramVo.setMerchantNo(application.getMerchantNo());

				HandleRefundExceptionCollectorCall collectCall = new HandleRefundExceptionCollectorCall(application);

				// 创建每条指令的采集任务对象
				FutureTask<TaskResultInfo> collectTask = new FutureTask<TaskResultInfo>(collectCall);

				// 添加到list,方便后面取得结果
				taskList.add(collectTask);
				// 提交给线程池 exec.submit(task);
				collectExec.submit(collectTask);
			}

			// 阻塞主线程,等待采集所有子线程结束,获取所有子线程的执行结果,get方法阻塞主线程,再继续执行主线程逻辑
			try {
				List<TaskResultInfo> updateTradeOrder = new ArrayList<TaskResultInfo>();
				for (FutureTask<TaskResultInfo> taskF : taskList) {
					// 防止某个子线程查询时间过长 超过默认时间没有拿到抛出异常
					TaskResultInfo info = taskF.get(Long.MAX_VALUE, TimeUnit.DAYS);
					if (info != null) {
						//updateRecord.add(record);
					}
				}

				if (updateTradeOrder.size() > 0) {
					//批量更新RegisterRecord
				}

			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			// 处理完毕,关闭线程池,这个不能在获取子线程结果之前关闭,因为如果线程多的话,执行中的可能被打断
			collectExec.shutdown();
		}

	}
}
