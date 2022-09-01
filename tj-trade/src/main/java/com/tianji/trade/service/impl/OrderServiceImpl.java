package com.tianji.trade.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CoursePurchaseInfoDTO;
import com.tianji.api.dto.order.OrderBasicDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.Constant;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.pay.sdk.dto.PayResultDTO;
import com.tianji.trade.config.TradeProperties;
import com.tianji.trade.constants.OrderStatus;
import com.tianji.trade.constants.RefundStatus;
import com.tianji.trade.constants.TradeErrorInfo;
import com.tianji.trade.domain.dto.PlaceOrderDTO;
import com.tianji.trade.domain.po.Order;
import com.tianji.trade.domain.po.OrderDetail;
import com.tianji.trade.domain.query.OrderPageQuery;
import com.tianji.trade.domain.vo.OrderDetailVO;
import com.tianji.trade.domain.vo.OrderPageVO;
import com.tianji.trade.domain.vo.OrderVO;
import com.tianji.trade.domain.vo.PlaceOrderResultVO;
import com.tianji.trade.mapper.OrderMapper;
import com.tianji.trade.service.ICartService;
import com.tianji.trade.service.IOrderDetailService;
import com.tianji.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.tianji.common.constants.ErrorInfo.Msg.OPERATE_FAILED;
import static com.tianji.trade.constants.TradeErrorInfo.ORDER_ALREADY_FINISH;
import static com.tianji.trade.constants.TradeErrorInfo.ORDER_NOT_EXISTS;

/**
 * <p>
 * 订单 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-08-29
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final CourseClient courseClient;
    private final IOrderDetailService detailService;
    private final ICartService cartService;
    private final TradeProperties tradeProperties;
    private final RabbitMqHelper rabbitMqHelper;

    @Override
    @Transactional
    public PlaceOrderResultVO placeOrder(PlaceOrderDTO placeOrderDTO) {
        Long userId = UserContext.getUser();
        // 1.查询课程费用信息，如果不可购买，这里直接报错
        List<CoursePurchaseInfoDTO> coursePurchaseInfos = courseClient.checkCoursePurchase(placeOrderDTO.getCourseIds());

        // 2.封装订单信息
        Order order = new Order();
        // 2.1.计算订单金额
        Integer totalAmount = coursePurchaseInfos.stream()
                .map(CoursePurchaseInfoDTO::getPrice).reduce(Integer::sum).orElse(0);
        // TODO 2.2.计算优惠金额
        Integer discountAmount = 0;
        Integer realAmount = totalAmount - discountAmount;
        // 2.3.封装其它信息
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setDiscountAmount(discountAmount);
        order.setRealAmount(realAmount);
        order.setStatus(OrderStatus.NO_PAY.getValue());
        // 2.4.订单id
        Long orderId = IdWorker.getId(order);
        order.setId(orderId);

        // 3.封装订单详情
        List<OrderDetail> orderDetails = new ArrayList<>(coursePurchaseInfos.size());
        for (CoursePurchaseInfoDTO courseInfo : coursePurchaseInfos) {
            orderDetails.add(packageOrderDetail(courseInfo, order));
        }

        // 4.写入数据库
        saveOrderAndDetails(order, orderDetails);

        // 5.删除购物车数据
        cartService.deleteCartByUserAndCourseIds(userId, placeOrderDTO.getCourseIds());

        // 6.构建下单结果
        return PlaceOrderResultVO.builder()
                .orderId(orderId)
                .payAmount(realAmount)
                .status(order.getStatus())
                .payOutTime(LocalDateTime.now().plusMinutes(tradeProperties.getPayOrderTTLMinutes()))
                .build();
    }


    @Override
    @Transactional
    public PlaceOrderResultVO enrolledFreeCourse(Long courseId) {
        Long userId = UserContext.getUser();
        // 1.查询课程信息
        List<Long> cIds = CollUtils.singletonList(courseId);
        List<CoursePurchaseInfoDTO> courseInfos = courseClient.checkCoursePurchase(cIds);
        if (CollUtils.isEmpty(courseInfos)) {
            // 课程不存在
            throw new BizIllegalException(TradeErrorInfo.COURSE_NOT_EXISTS);
        }
        CoursePurchaseInfoDTO courseInfo = courseInfos.get(0);
        if(!courseInfo.getFree()){
            // 非免费课程，直接报错
            throw new BizIllegalException(TradeErrorInfo.COURSE_NOT_FREE);
        }
        // 2.创建订单
        Order order = new Order();
        // 2.1.基本信息
        order.setUserId(userId);
        order.setTotalAmount(0);
        order.setDiscountAmount(0);
        order.setRealAmount(0);
        order.setStatus(OrderStatus.ENROLLED.getValue());
        order.setFinishTime(LocalDateTime.now());
        order.setMessage(OrderStatus.ENROLLED.getProgressName());
        // 2.2.订单id
        Long orderId = IdWorker.getId(order);
        order.setId(orderId);

        // 3.订单详情
        OrderDetail detail = packageOrderDetail(courseInfo, order);

        // 4.写入数据库
        saveOrderAndDetails(order, CollUtils.singletonList(detail));

        // 5.发送MQ消息，通知报名成功
        rabbitMqHelper.send(
                MqConstants.Exchange.ORDER_EXCHANGE,
                MqConstants.Key.ORDER_PAY_KEY,
                OrderBasicDTO.builder().orderId(orderId).userId(userId).courseIds(cIds).build());
        // 6.返回vo
        return PlaceOrderResultVO.builder()
                .orderId(orderId)
                .payAmount(0)
                .status(order.getStatus())
                .build();
    }

    private OrderDetail packageOrderDetail(CoursePurchaseInfoDTO courseInfo, Order order) {
        OrderDetail detail = new OrderDetail();
        detail.setUserId(order.getUserId());
        detail.setOrderId(order.getId());
        detail.setStatus(order.getStatus());
        detail.setCourseId(courseInfo.getId());
        detail.setPrice(courseInfo.getPrice());
        detail.setCoverUrl(courseInfo.getCoverUrl());
        detail.setName(courseInfo.getName());
        detail.setValidDuration(courseInfo.getValidDuration());
        detail.setDiscountAmount(0);// TODO 计算优惠金额
        detail.setRealPayAmount(courseInfo.getPrice() - detail.getDiscountAmount());
        return detail;
    }

    @Override
    @Transactional
    public void saveOrderAndDetails(Order order, List<OrderDetail> orderDetails) {
        // 4.1.写订单
        boolean success = save(order);
        if (!success) {
            throw new DbException(TradeErrorInfo.PLACE_ORDER_FAILED);
        }
        // 4.2.写订单详情
        if(orderDetails.size() == 1){
            success = detailService.save(orderDetails.get(0));
        }else {
            success = detailService.saveBatch(orderDetails);
        }
        if (!success) {
            throw new DbException(TradeErrorInfo.PLACE_ORDER_FAILED);
        }
    }

    @Override
    @Transactional
    public void cancelOrder(Long orderId) {
        Long userId = UserContext.getUser();
        // 1.查询订单
        Order order = getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BadRequestException(ORDER_NOT_EXISTS);
        }
        // 2.判断订单状态是否已经取消，幂等判断
        if(OrderStatus.CLOSED.equalsValue(order.getStatus())){
           // 订单已经取消，无需重复操作
           return;
        }
        // 3.判断订单是否未支付，只有未支付订单才可以取消
        if(!OrderStatus.NO_PAY.equalsValue(order.getStatus())){
            throw new BizIllegalException(ORDER_ALREADY_FINISH);
        }
        // 4.可以更新订单状态为取消了
        boolean success = lambdaUpdate()
                .set(Order::getStatus, OrderStatus.CLOSED.getValue())
                .set(Order::getMessage, "用户取消订单")
                .set(Order::getCloseTime, LocalDateTime.now())
                .eq(Order::getStatus, OrderStatus.NO_PAY.getValue())
                .eq(Order::getId, orderId)
                .update();
        if (!success) {
            return;
        }
        // 5.更新订单条目的状态
        detailService.updateStatusByOrderId(orderId, OrderStatus.CLOSED.getValue());
    }

    @Override
    public void deleteOrder(Long id) {
        boolean success = removeById(id);
        if (success) {
            throw new DbException(OPERATE_FAILED);
        }
    }

    @Override
    public PageDTO<OrderPageVO> queryMyOrderPage(OrderPageQuery pageQuery) {
        Long userId = UserContext.getUser();
        // 1.分页排序条件
        Page<Order> p = pageQuery.toMpPage(Constant.DATA_FIELD_NAME_CREATE_TIME, false);
        // 2.分页查询订单
        Integer status = pageQuery.getStatus();
        Page<Order> page = lambdaQuery()
                .eq(status != null, Order::getStatus, status)
                .eq(Order::getUserId, userId)
                .page(p);
        // 3.数据判断
        List<Order> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(p);
        }
        // 4.查询订单明细信息
        List<Long> orderIds = records.stream().map(Order::getId).collect(Collectors.toList());
        // 4.1.根据订单id查询订单明细
        List<OrderDetail> details = detailService.queryByOrderIds(orderIds);
        // 4.2.将订单明细分组，key是订单id，值是订单下的所有detail
        Map<Long, List<OrderDetailVO>> detailMap = details.stream()
                .map(od -> BeanUtils.copyBean(od, OrderDetailVO.class, (d, v) -> v.setCanRefund(
                        // 订单已经支付，且 退款没有在进行中，标记为可退款状态
                        OrderStatus.canRefund(d.getStatus()) && !RefundStatus.inProgress(v.getRefundStatus())
                )))
                .collect(Collectors.groupingBy(OrderDetailVO::getOrderId));
        // 5.转换VO
        List<OrderPageVO> list = new ArrayList<>(orderIds.size());
        for (Order record : records) {
            // 5.1.转换订单
            OrderPageVO v = BeanUtils.toBean(record, OrderPageVO.class);
            list.add(v);
            // 5.2.写入vo
            v.setDetails(detailMap.get(record.getId()));
            v.setStatusDesc(OrderStatus.desc(v.getStatus()));
        }
        return PageDTO.of(page, list);
    }

    @Override
    public OrderVO queryOrderById(Long id) {
        // 1.查询订单
        Order order = getById(id);
        if (order == null) {
            throw new BadRequestException(ORDER_NOT_EXISTS);
        }
        // 2.查询订单详情
        List<OrderDetail> details = detailService.queryByOrderId(id);
        // 3.转换VO
        // 3.1.订单
        OrderVO vo = BeanUtils.toBean(order, OrderVO.class);
        // 3.2.订单详情
        List<OrderDetailVO> dvs = BeanUtils.copyList(details, OrderDetailVO.class);
        vo.setDetails(dvs);
        // 3.3.订单进度
        vo.setProgressNodes(detailService.packageProgressNodes(order, null));
        return vo;
    }

    @Override
    public PlaceOrderResultVO queryOrderStatus(Long orderId) {
        // 1.查询订单
        Order order = getById(orderId);
        if (order == null) {
            throw new BizIllegalException(ORDER_NOT_EXISTS);
        }
        // 2.计算超时时间
        LocalDateTime outTime = null;
        if(OrderStatus.NO_PAY.equalsValue(order.getStatus())){
            outTime = order.getCreateTime().plusMinutes(tradeProperties.getPayOrderTTLMinutes());
        }
        // 3.封装结果
        return PlaceOrderResultVO.builder()
                .orderId(orderId)
                .payAmount(order.getRealAmount())
                .status(order.getStatus())
                .payOutTime(outTime)
                .build();
    }

    @Override
    @Transactional
    public void handlePaySuccess(PayResultDTO payResult) {
        // 1.查询订单
        Order order = getById(payResult.getBizOrderId());
        if (order == null) {
            return;
        }
        // 2.更新订单状态
        Order o = new Order();
        o.setId(order.getId());
        o.setStatus(OrderStatus.PAYED.getValue());
        o.setPayTime(payResult.getSuccessTime());
        o.setPayChannel(payResult.getPayChannel());
        o.setPayOrderNo(payResult.getPayOrderNo());
        o.setMessage("用户支付成功");
        updateById(o);
        // 3.更新订单条目
        detailService.markDetailSuccessByOrderId(o.getId(), payResult.getPayChannel());
        // 4.查询订单包含的课程信息
        List<Long> cIds = detailService.queryCourseIdsByOrderId(o.getId());
        // 5.发送MQ消息，通知报名成功
        rabbitMqHelper.send(
                MqConstants.Exchange.ORDER_EXCHANGE,
                MqConstants.Key.PAY_SUCCESS,
                OrderBasicDTO.builder().orderId(o.getId()).userId(order.getUserId()).courseIds(cIds).build());
    }

}
