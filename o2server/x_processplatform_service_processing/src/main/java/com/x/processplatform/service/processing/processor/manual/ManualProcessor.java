package com.x.processplatform.service.processing.processor.manual;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.script.Bindings;
import javax.script.ScriptContext;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.time.DateUtils;

import com.x.base.core.container.EntityManagerContainer;
import com.x.base.core.entity.JpaObject;
import com.x.base.core.project.config.Config;
import com.x.base.core.project.logger.Logger;
import com.x.base.core.project.logger.LoggerFactory;
import com.x.base.core.project.organization.EmpowerLog;
import com.x.base.core.project.script.ScriptFactory;
import com.x.base.core.project.tools.DateTools;
import com.x.base.core.project.tools.ListTools;
import com.x.base.core.project.tools.NumberTools;
import com.x.base.core.project.utils.time.WorkTime;
import com.x.processplatform.core.entity.content.Hint;
import com.x.processplatform.core.entity.content.ProcessingType;
import com.x.processplatform.core.entity.content.Read;
import com.x.processplatform.core.entity.content.Task;
import com.x.processplatform.core.entity.content.TaskCompleted;
import com.x.processplatform.core.entity.content.Work;
import com.x.processplatform.core.entity.content.WorkLog;
import com.x.processplatform.core.entity.element.ActivityType;
import com.x.processplatform.core.entity.element.Manual;
import com.x.processplatform.core.entity.element.Route;
import com.x.processplatform.core.entity.element.util.WorkLogTree;
import com.x.processplatform.service.processing.Business;
import com.x.processplatform.service.processing.processor.AeiObjects;

public class ManualProcessor extends AbstractManualProcessor {

	private static Logger logger = LoggerFactory.getLogger(ManualProcessor.class);

	public ManualProcessor(EntityManagerContainer entityManagerContainer) throws Exception {
		super(entityManagerContainer);
	}

	@Override
	protected Work arriving(AeiObjects aeiObjects, Manual manual) throws Exception {
		/* 根据manual计算出来的活动处理人 */
		List<String> identities = calculateTaskIdentities(aeiObjects, manual);
		/* 启用同类工作相同活动节点合并,如果有合并的工作,那么直接返回这个工作. */
		if (BooleanUtils.isTrue(manual.getManualMergeSameJobActivity())) {
			List<String> exists = this.arriving_sameJobActivityExistIdentities(aeiObjects, manual);
			if (ListTools.isNotEmpty(exists)) {
				Work other = aeiObjects.getWorks().stream().filter(o -> {
					return StringUtils.equals(aeiObjects.getWork().getJob(), o.getJob())
							&& StringUtils.equals(aeiObjects.getWork().getActivity(), o.getActivity())
							&& (!Objects.equals(aeiObjects.getWork(), o));
				}).findFirst().orElse(null);
				if (null != other) {
					identities.removeAll(exists);
					if (ListTools.isEmpty(identities)) {
						this.mergeTaskCompleted(aeiObjects, aeiObjects.getWork(), other);
						this.mergeRead(aeiObjects, aeiObjects.getWork(), other);
						this.mergeReadCompleted(aeiObjects, aeiObjects.getWork(), other);
						this.mergeReview(aeiObjects, aeiObjects.getWork(), other);
						this.mergeHint(aeiObjects, aeiObjects.getWork(), other);
						this.mergeAttachment(aeiObjects, aeiObjects.getWork(), other);
						this.mergeWorkLog(aeiObjects, aeiObjects.getWork(), other);
						if (ListTools.size(aeiObjects.getWork().getSplitTokenList()) > ListTools
								.size(other.getSplitTokenList())) {
							other.setSplitTokenList(aeiObjects.getWork().getSplitTokenList());
							other.setSplitToken(aeiObjects.getWork().getSplitToken());
							other.setSplitValue(aeiObjects.getWork().getSplitValue());
							other.setSplitting(true);
						}
						aeiObjects.getUpdateWorks().add(other);
						aeiObjects.getDeleteWorks().add(aeiObjects.getWork());
						return other;
					}
				}
			}
		}

		aeiObjects.getWork().setManualTaskIdentityList(new ArrayList<String>(identities));
		/* 查找是否有passSameTarget设置 */
		Route passSameTargetRoute = aeiObjects.getRoutes().stream()
				.filter(o -> BooleanUtils.isTrue(o.getPassSameTarget())).findFirst().orElse(null);
		/* 如果有passSameTarget,有到达ArriveWorkLog,不是调度到这个节点的 */
		if ((null != passSameTargetRoute) && ((null != aeiObjects.getArriveWorkLog(aeiObjects.getWork())))
				&& (BooleanUtils.isNotTrue(aeiObjects.getWork().getForceRouteArriveCurrentActivity()))) {
			logger.debug("pass same target work:{}.", aeiObjects.getWork());
			WorkLog rollbackWorkLog = findPassSameTargetWorkLog(aeiObjects);
			logger.debug("pass same target workLog:{}.", rollbackWorkLog);
			if (null != rollbackWorkLog) {
				final String arriveActivityToken = rollbackWorkLog.getArrivedActivityToken();
				aeiObjects.getJoinInquireTaskCompleteds().stream()
						.filter(o -> aeiObjects.getWork().getManualTaskIdentityList().contains(o.getIdentity())
								&& StringUtils.equals(o.getActivityToken(), arriveActivityToken))
						.forEach(o -> {
							TaskCompleted obj = new TaskCompleted(aeiObjects.getWork(), passSameTargetRoute, o);
							try {
								obj.setProcessingType(ProcessingType.sameTarget);
								obj.setRouteName(passSameTargetRoute.getName());
								Date now = new Date();
								obj.setStartTime(now);
								obj.setStartTimeMonth(DateTools.format(now, DateTools.format_yyyyMM));
								obj.setCompletedTime(now);
								obj.setCompletedTimeMonth(DateTools.format(now, DateTools.format_yyyyMM));
								obj.setDuration(0L);
								obj.setExpired(false);
								obj.setExpireTime(null);
								obj.setTask(null);
								obj.setLatest(true);
								aeiObjects.getCreateTaskCompleteds().add(obj);
							} catch (Exception e) {
								e.printStackTrace();
							}
						});
			}
		}
		return aeiObjects.getWork();
	}

	/* 计算处理人 */
	private List<String> calculateTaskIdentities(AeiObjects aeiObjects, Manual manual) throws Exception {
		TaskIdentities taskIdentities = TranslateTaskIdentityTools.translate(aeiObjects, manual);
		this.ifTaskIdentitiesEmptyForceToCreatorOrMaintenance(aeiObjects, taskIdentities);
		this.writeToEmpowerMap(aeiObjects, taskIdentities);
		return taskIdentities.identities();
	}

	/* 如果活动没有找到任何可用的处理人,那么强制设置处理人为文档创建者,或者配置的 maintenanceIdentity */
	private void ifTaskIdentitiesEmptyForceToCreatorOrMaintenance(AeiObjects aeiObjects, TaskIdentities taskIdentities)
			throws Exception {
		if (taskIdentities.isEmpty()) {
			String identity = aeiObjects.business().organization().identity()
					.get(aeiObjects.getWork().getCreatorIdentity());
			if (StringUtils.isNotEmpty(identity)) {
				logger.info("人工活动到达未能找到指定的处理人, 标题:{}, id:{}, 强制指定处理人为活动的创建身份:{}.", aeiObjects.getWork().getTitle(),
						aeiObjects.getWork().getId(), identity);
				taskIdentities.addIdentity(identity);
			} else {
				identity = aeiObjects.business().organization().identity()
						.get(Config.processPlatform().getMaintenanceIdentity());
				if (StringUtils.isNotEmpty(identity)) {
					logger.info("人工活动到达未能找到指定的处理人, 标题:{}, id:{}, 也没有能找到工作创建人,  强制指定处理人为系统维护身份:{}.",
							aeiObjects.getWork().getTitle(), aeiObjects.getWork().getId(), identity);
					taskIdentities.addIdentity(identity);
				} else {
					throw new ExceptionExpectedEmpty(aeiObjects.getWork().getTitle(), aeiObjects.getWork().getId(),
							aeiObjects.getActivity().getName(), aeiObjects.getActivity().getId());
				}
			}
			aeiObjects.createHint(
					Hint.EmptyTaskIdentityOnManual(aeiObjects.getWork(), (Manual) aeiObjects.getActivity()));
		}
	}

	/*
	 * 更新授权,通过surface创建且workThroughManual=false 代表是草稿,那么不需要授权.
	 */
	private void writeToEmpowerMap(AeiObjects aeiObjects, TaskIdentities taskIdentities) throws Exception {
		/*
		 * 先清空EmpowerMap
		 */
		aeiObjects.getWork().getManualEmpowerMap().clear();
		if (!(StringUtils.equals(aeiObjects.getWork().getWorkCreateType(), Work.WORKCREATETYPE_SURFACE)
				&& BooleanUtils.isFalse(aeiObjects.getWork().getWorkThroughManual()))) {
			taskIdentities.empower(aeiObjects.business().organization().empower().listWithIdentityObject(
					aeiObjects.getWork().getApplication(), aeiObjects.getWork().getProcess(),
					aeiObjects.getWork().getId(), taskIdentities.identities()));
			for (TaskIdentity taskIdentity : taskIdentities) {
				if (StringUtils.isNotEmpty(taskIdentity.getFromIdentity())) {
					aeiObjects.getWork().getManualEmpowerMap().put(taskIdentity.getIdentity(),
							taskIdentity.getFromIdentity());
				}
			}
		}
	}

	private WorkLog findPassSameTargetWorkLog(AeiObjects aeiObjects) throws Exception {
		WorkLogTree tree = new WorkLogTree(aeiObjects.getWorkLogs());
		List<WorkLog> parents = tree.parents(aeiObjects.getArriveWorkLog(aeiObjects.getWork()));
		logger.debug("pass same target rollback parents:{}.", parents);
		WorkLog workLog = null;
		for (WorkLog o : parents) {
			if (Objects.equals(ActivityType.manual, o.getArrivedActivityType())) {
				workLog = o;
				break;
			} else if (Objects.equals(ActivityType.choice, o.getArrivedActivityType())) {
				continue;
			} else if (Objects.equals(ActivityType.agent, o.getArrivedActivityType())) {
				continue;
			} else if (Objects.equals(ActivityType.invoke, o.getArrivedActivityType())) {
				continue;
			} else if (Objects.equals(ActivityType.service, o.getArrivedActivityType())) {
				continue;
			} else {
				break;
			}
		}
		logger.debug("pass same target find workLog:{}.", workLog);
		return workLog;
	}

	@Override
	protected void arrivingCommitted(AeiObjects aeiObjects, Manual manual) throws Exception {
	}

	@Override
	protected List<Work> executing(AeiObjects aeiObjects, Manual manual) throws Exception {

		List<Work> results = new ArrayList<>();
		boolean passThrough = false;

		List<String> identities = aeiObjects.business().organization().identity()
				.list(aeiObjects.getWork().getManualTaskIdentityList());

		if (identities.isEmpty()) {
			identities = calculateTaskIdentities(aeiObjects, manual);
			logger.info("工作设置的处理人已经全部无效,重新计算当前环节所有处理人进行处理,标题:{}, id:{}, 设置的处理人:{}.", aeiObjects.getWork().getTitle(),
					aeiObjects.getWork().getId(), identities);
			/* 后面进行了identitis.remove()这里必须用一个新对象包装 */
			aeiObjects.getWork().setManualTaskIdentityList(new ArrayList<String>(identities));
		}

		switch (manual.getManualMode()) {
		case single:
			passThrough = this.single(aeiObjects, manual, identities);
			break;
		case parallel:
			passThrough = this.parallel(aeiObjects, manual, identities);
			break;
		case queue:
			passThrough = this.queue(aeiObjects, manual, identities);
			break;
		case grab:
			passThrough = this.single(aeiObjects, manual, identities);
			break;
		default:
			throw new ExceptionManualModeError(manual.getId());
		}
		if (passThrough) {
			results.add(aeiObjects.getWork());
		}
		return results;
	}

	@Override
	protected void executingCommitted(AeiObjects aeiObjects, Manual manual) throws Exception {

	}

	@Override
	protected List<Route> inquiring(AeiObjects aeiObjects, Manual manual) throws Exception {
		List<Route> results = new ArrayList<>();
		/* 仅有单条路由 */
		if (aeiObjects.getRoutes().size() == 1) {
			results.add(aeiObjects.getRoutes().get(0));
		} else if (aeiObjects.getRoutes().size() > 1) {
			/* 存在多条路由 */
			List<TaskCompleted> taskCompletedList = aeiObjects.getJoinInquireTaskCompleteds().stream()
					.filter(o -> StringUtils.equals(o.getActivityToken(), aeiObjects.getWork().getActivityToken())
							&& aeiObjects.getWork().getManualTaskIdentityList().contains(o.getIdentity()))
					.collect(Collectors.toList());

			String name = this.choiceRouteName(taskCompletedList, aeiObjects.getRoutes());
			for (Route o : aeiObjects.getRoutes()) {
				if (o.getName().equalsIgnoreCase(name)) {
					results.add(o);
					break;
				}
			}
		}
		return results;
	}

	/* 通过已办存根选择某条路由 */
	private String choiceRouteName(List<TaskCompleted> list, List<Route> routes) throws Exception {
		String result = "";
		List<String> names = new ArrayList<>();
		ListTools.trim(list, false, false).stream().forEach(o -> {
			names.add(o.getRouteName());
		});
		/* 进行优先路由的判断 */
		Route soleRoute = routes.stream().filter(o -> BooleanUtils.isTrue(o.getSole())).findFirst().orElse(null);
		if ((null != soleRoute) && names.contains(soleRoute.getName())) {
			result = soleRoute.getName();
		} else {
			/* 进行默认的策略,选择占比多的 */
			result = ListTools.maxCountElement(names);
		}
		if (StringUtils.isEmpty(result)) {
			throw new ExceptionChoiceRouteNameError(
					ListTools.extractProperty(list, JpaObject.id_FIELDNAME, String.class, false, false));
		}
		return result;
	}

	private boolean single(AeiObjects aeiObjects, Manual manual, List<String> identities) throws Exception {
		boolean passThrough = false;
		Long count = aeiObjects.getJoinInquireTaskCompleteds().stream().filter(o -> {
			if (StringUtils.equals(aeiObjects.getWork().getActivityToken(), o.getActivityToken())
					&& (identities.contains(o.getIdentity()))) {
				return true;
			} else {
				return false;
			}
		}).count();
		if (count > 0) {
			/* 已经确定要通过此节点,清除可能是多余的待办 */
			aeiObjects.getTasks().stream().filter(o -> {
				return StringUtils.equals(aeiObjects.getWork().getId(), o.getWork());
			}).forEach(o -> {
				aeiObjects.deleteTask(o);
			});
			/* 所有预计的处理人中已经有已办,这个环节已经产生了已办，可以离开换个环节。 */
			passThrough = true;
		} else {
			/* 取到期望的待办人员，由于要进行处理需要转换成可读写List */
			if (ListTools.isEmpty(identities)) {
				throw new ExceptionExpectedEmpty(aeiObjects.getWork().getTitle(), aeiObjects.getWork().getId(),
						manual.getName(), manual.getId());
			}
			/* 删除多余的待办 */
			aeiObjects.getTasks().stream()
					.filter(o -> StringUtils.equals(aeiObjects.getWork().getActivityToken(), o.getActivityToken())
							&& (!ListTools.contains(identities, o.getIdentity())))
					.forEach(o -> {
						aeiObjects.deleteTask(o);
					});
			/* 将待办已经产生的人从预期值中删除 */
			aeiObjects.getTasks().stream()
					.filter(o -> StringUtils.equals(aeiObjects.getWork().getActivityToken(), o.getActivityToken())
							&& (ListTools.contains(identities, o.getIdentity())))
					.forEach(o -> {
						identities.remove(o.getIdentity());
					});
			/* 这里剩余的应该是没有生成待办的人员 */
			if (!identities.isEmpty()) {
				for (String identity : identities) {
					aeiObjects.createTask(this.createTask(aeiObjects, manual, identity));
				}
			}
		}
		return passThrough;
	}

	private boolean parallel(AeiObjects aeiObjects, Manual manual, List<String> identities) throws Exception {
		boolean passThrough = false;
		/* 取得本环节已经处理的已办 */
		List<TaskCompleted> taskCompleteds = this.listJoinInquireTaskCompleted(aeiObjects, identities);
		/* 存在优先路由 */
		Route soleRoute = aeiObjects.getRoutes().stream().filter(r -> BooleanUtils.isTrue(r.getSole())).findFirst()
				.orElse(null);
		if (null != soleRoute) {
			TaskCompleted soleTaskCompleted = taskCompleteds.stream()
					.filter(t -> BooleanUtils.isTrue(t.getJoinInquire())
							&& StringUtils.equals(t.getRouteName(), soleRoute.getName()))
					.findFirst().orElse(null);
			if (null != soleTaskCompleted) {
				return true;
			}
		}
		/* 存在优先路由结束 */
		/* 将已经处理的人从期望值中移除 */
		aeiObjects.getJoinInquireTaskCompleteds().stream().filter(o -> {
			return StringUtils.equals(aeiObjects.getWork().getActivityToken(), o.getActivityToken());
		}).forEach(o -> {
			identities.remove(o.getIdentity());
		});
		/* 清空可能的多余的待办 */
		aeiObjects.getTasks().stream().filter(o -> {
			return StringUtils.equals(aeiObjects.getWork().getActivityToken(), o.getActivityToken())
					&& (!ListTools.contains(identities, o.getIdentity()));
		}).forEach(o -> {
			aeiObjects.deleteTask(o);
		});
		if (identities.isEmpty()) {
			/* 所有人已经处理完成。 */
			passThrough = true;
		} else {
			passThrough = false;
			/* 先清空已经有待办的身份 */
			aeiObjects.getTasks().stream().filter(o -> {
				return StringUtils.equals(aeiObjects.getWork().getActivityToken(), o.getActivityToken());
			}).forEach(o -> {
				identities.remove(o.getIdentity());
			});
			/* 这里剩余的应该是没有生成待办的人员 */
			if (!identities.isEmpty()) {
				for (String identity : identities) {
					aeiObjects.createTask(this.createTask(aeiObjects, manual, identity));
				}
			}
		}
		return passThrough;
	}

	private boolean queue(AeiObjects aeiObjects, Manual manual, List<String> identities) throws Exception {
		boolean passThrough = false;
		List<TaskCompleted> taskCompleteds = this.listJoinInquireTaskCompleted(aeiObjects, identities);
		/* 存在优先路由 */
		Route soleRoute = aeiObjects.getRoutes().stream().filter(r -> BooleanUtils.isTrue(r.getSole())).findFirst()
				.orElse(null);
		if (null != soleRoute) {
			TaskCompleted soleTaskCompleted = taskCompleteds.stream()
					.filter(t -> BooleanUtils.isTrue(t.getJoinInquire())
							&& StringUtils.equals(t.getRouteName(), soleRoute.getName()))
					.findFirst().orElse(null);
			if (null != soleTaskCompleted) {
				return true;
			}
		}
		/* 存在优先路由结束 */

		/* 将已经处理的人从期望值中移除 */
		for (TaskCompleted o : taskCompleteds) {
			identities.remove(o.getIdentity());
		}
		if (identities.isEmpty()) {
			/* 所有人已经处理完成。 */
			passThrough = true;
		} else {
			passThrough = false;
			String identity = identities.get(0);
			/* 还有人没有处理，开始判断待办,取到本环节的所有待办,理论上只能有一条待办 */
			boolean find = false;
			for (Task t : aeiObjects.getTasks()) {
				if (StringUtils.equals(aeiObjects.getWork().getActivityToken(), t.getActivityToken())) {
					if (!StringUtils.equals(t.getIdentity(), identity)) {
						aeiObjects.deleteTask(t);
					} else {
						find = true;
					}
				}
			}
			/* 当前处理人没有待办 */
			if (!find) {
				aeiObjects.createTask(this.createTask(aeiObjects, manual, identity));
			}
		}
		return passThrough;

	}

	/* 所有有效的已办,去除 reset,retract,appendTask */
	private List<TaskCompleted> listJoinInquireTaskCompleted(AeiObjects aeiObjects, List<String> identities)
			throws Exception {
		return aeiObjects.getJoinInquireTaskCompleteds().stream().filter(o -> {
			return StringUtils.equals(aeiObjects.getWork().getActivityToken(), o.getActivityToken())
					&& identities.contains(o.getIdentity());
		}).collect(Collectors.toList());
	}

	@Override
	protected void inquiringCommitted(AeiObjects aeiObjects, Manual manual) throws Exception {
	}

	private void calculateExpire(AeiObjects aeiObjects, Manual manual, Task task) throws Exception {
		if (null != manual.getTaskExpireType()) {
			switch (manual.getTaskExpireType()) {
			case never:
				this.expireNever(task);
				break;
			case appoint:
				this.expireAppoint(manual, task);
				break;
			case script:
				this.expireScript(aeiObjects, manual, task);
				break;
			default:
				break;
			}
		}
		/* 如果work有截至时间 */
		if (null != aeiObjects.getWork().getExpireTime()) {
			if (null == task.getExpireTime()) {
				task.setExpireTime(aeiObjects.getWork().getExpireTime());
			} else {
				if (task.getExpireTime().after(aeiObjects.getWork().getExpireTime())) {
					task.setExpireTime(aeiObjects.getWork().getExpireTime());
				}
			}
		}
		/* 已经有过期时间了,那么设置催办时间 */
		if (null != task.getExpireTime()) {
			task.setUrgeTime(DateUtils.addHours(task.getExpireTime(), -2));
		} else {
			task.setExpired(false);
			task.setUrgeTime(null);
			task.setUrged(false);
		}
	}

	/*
	 * 从不过期
	 */
	private void expireNever(Task task) {
		task.setExpireTime(null);
	}

	private void expireAppoint(Manual manual, Task task) throws Exception {
		if (BooleanUtils.isTrue(manual.getTaskExpireWorkTime())) {
			this.expireAppointWorkTime(task, manual);
		} else {
			this.expireAppointNaturalDay(task, manual);
		}
	}

	private void expireAppointWorkTime(Task task, Manual manual) throws Exception {
		Integer m = 0;
		WorkTime wt = new WorkTime();
		if (NumberTools.greaterThan(manual.getTaskExpireDay(), 0)) {
			m += manual.getTaskExpireDay() * wt.minutesOfWorkDay();
		}
		if (NumberTools.greaterThan(manual.getTaskExpireHour(), 0)) {
			m += manual.getTaskExpireHour() * 60;
		}
		if (m > 0) {
			Date expire = wt.forwardMinutes(new Date(), m);
			task.setExpireTime(expire);
		} else {
			task.setExpireTime(null);
		}
	}

	private void expireAppointNaturalDay(Task task, Manual manual) throws Exception {
		Integer m = 0;
		if (NumberTools.greaterThan(manual.getTaskExpireDay(), 0)) {
			m += manual.getTaskExpireDay() * 60 * 24;
		}
		if (NumberTools.greaterThan(manual.getTaskExpireHour(), 0)) {
			m += manual.getTaskExpireHour() * 60;
		}
		if (m > 0) {
			Calendar cl = Calendar.getInstance();
			cl.add(Calendar.MINUTE, m);
			task.setExpireTime(cl.getTime());
		} else {
			task.setExpireTime(null);
		}
	}

	private void expireScript(AeiObjects aeiObjects, Manual manual, Task task) throws Exception {
		ScriptContext scriptContext = aeiObjects.scriptContext();
		Bindings bindings = scriptContext.getBindings(ScriptContext.ENGINE_SCOPE);
		bindings.put(ScriptFactory.BINDING_NAME_TASK, task);
		Object objectValue = aeiObjects.business().element()
				.getCompiledScript(aeiObjects.getWork().getApplication(), manual, Business.EVENT_MANUALTASKEXPIRE)
				.eval(scriptContext);
		WorkTime wt = new WorkTime();
		if (NumberUtils.isCreatable(objectValue.toString())) {
			task.setExpireTime(wt.forwardMinutes(new Date(), NumberUtils.toInt(ScriptFactory.asString(objectValue))));
		} else {
			task.setExpireTime(null);
		}

	}

	private Task createTask(AeiObjects aeiObjects, Manual manual, String identity) throws Exception {
		String fromIdentity = aeiObjects.getWork().getManualEmpowerMap().get(identity);
		String person = aeiObjects.business().organization().person().getWithIdentity(identity);
		String unit = aeiObjects.business().organization().unit().getWithIdentity(identity);
		Task task = new Task(aeiObjects.getWork(), identity, person, unit, fromIdentity, new Date(), null,
				aeiObjects.getRoutes(), manual.getAllowRapid());
		/* 是第一条待办,进行标记 */
		if (ListTools.isEmpty(aeiObjects.getJoinInquireTaskCompleteds())) {
			task.setFirst(true);
		} else {
			task.setFirst(false);
		}
		this.calculateExpire(aeiObjects, manual, task);
		if (StringUtils.isNotEmpty(fromIdentity)) {
			aeiObjects.business().organization().empowerLog()
					.log(this.createEmpowerLog(aeiObjects.getWork(), fromIdentity, identity));
			String fromPerson = aeiObjects.business().organization().person().getWithIdentity(fromIdentity);
			String fromUnit = aeiObjects.business().organization().unit().getWithIdentity(fromIdentity);
			TaskCompleted empowerTaskCompleted = new TaskCompleted(aeiObjects.getWork());
			empowerTaskCompleted.setProcessingType(ProcessingType.empower);
			empowerTaskCompleted.setIdentity(fromIdentity);
			empowerTaskCompleted.setUnit(fromUnit);
			empowerTaskCompleted.setPerson(fromPerson);
			empowerTaskCompleted.setEmpowerToIdentity(identity);
			aeiObjects.createTaskCompleted(empowerTaskCompleted);
			Read empowerRead = new Read(aeiObjects.getWork(), fromIdentity, fromUnit, fromPerson);
			aeiObjects.createRead(empowerRead);
		}
		return task;
	}

	private EmpowerLog createEmpowerLog(Work work, String fromIdentity, String toIdentity) {
		EmpowerLog empowerLog = new EmpowerLog().setApplication(work.getApplication())
				.setApplicationAlias(work.getApplicationAlias()).setApplicationName(work.getApplicationName())
				.setProcess(work.getProcess()).setProcessAlias(work.getProcessAlias())
				.setProcessName(work.getProcessName()).setTitle(work.getTitle()).setWork(work.getId())
				.setJob(work.getJob()).setFromIdentity(fromIdentity).setToIdentity(toIdentity)
				.setActivity(work.getActivity()).setActivityAlias(work.getActivityAlias())
				.setActivityName(work.getActivityName()).setEmpowerTime(new Date());
		return empowerLog;
	}

	private List<String> arriving_sameJobActivityExistIdentities(AeiObjects aeiObjects, Manual manual)
			throws Exception {
		List<String> exists = new ArrayList<>();
		aeiObjects.getTasks().stream().filter(o -> {
			return StringUtils.equals(o.getActivity(), manual.getId())
					&& StringUtils.equals(o.getJob(), aeiObjects.getWork().getJob());
		}).forEach(o -> {
			exists.add(o.getIdentity());
		});
		return exists;
	}

	public class ExpireScriptResult {
		Integer hour;
		Integer workHour;
		Date date;

		public Integer getHour() {
			return hour;
		}

		public void setHour(Integer hour) {
			this.hour = hour;
		}

		public Integer getWorkHour() {
			return workHour;
		}

		public void setWorkHour(Integer workHour) {
			this.workHour = workHour;
		}

		public Date getDate() {
			return date;
		}

		public void setDate(Date date) {
			this.date = date;
		}

		public void setDate(String str) {
			try {
				this.date = DateTools.parse(str);
			} catch (Exception e) {
				logger.error(e);
			}
		}

	}
}