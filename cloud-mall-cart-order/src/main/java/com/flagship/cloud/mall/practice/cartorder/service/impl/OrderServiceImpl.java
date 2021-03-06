package com.flagship.cloud.mall.practice.cartorder.service.impl;

import com.flagship.cloud.mall.practice.cartorder.feign.ProductFeignClient;
import com.flagship.cloud.mall.practice.cartorder.feign.UserFeignClient;
import com.flagship.cloud.mall.practice.cartorder.model.dao.CartMapper;
import com.flagship.cloud.mall.practice.cartorder.model.dao.OrderItemMapper;
import com.flagship.cloud.mall.practice.cartorder.model.dao.OrderMapper;
import com.flagship.cloud.mall.practice.cartorder.model.pojo.Order;
import com.flagship.cloud.mall.practice.cartorder.model.pojo.OrderItem;
import com.flagship.cloud.mall.practice.cartorder.model.request.CreateOrderReq;
import com.flagship.cloud.mall.practice.cartorder.model.vo.CartVO;
import com.flagship.cloud.mall.practice.cartorder.model.vo.OrderItemVO;
import com.flagship.cloud.mall.practice.cartorder.model.vo.OrderVO;
import com.flagship.cloud.mall.practice.cartorder.service.CartService;
import com.flagship.cloud.mall.practice.cartorder.service.OrderService;
import com.flagship.cloud.mall.practice.cartorder.util.OrderCodeFactory;
import com.flagship.cloud.mall.practice.categoryproduct.common.ProductConstant;
import com.flagship.cloud.mall.practice.categoryproduct.model.dao.ProductMapper;
import com.flagship.cloud.mall.practice.categoryproduct.model.pojo.Product;
import com.flagship.cloud.mall.practice.common.common.Constant;
import com.flagship.cloud.mall.practice.common.exception.FlagshipMallException;
import com.flagship.cloud.mall.practice.common.exception.FlagshipMallExceptionEnum;
import com.flagship.cloud.mall.practice.common.util.QrCodeGenerator;
import com.flagship.cloud.mall.practice.user.model.pojo.User;
import com.flagship.cloud.mall.practice.user.service.UserService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.zxing.WriterException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Author Flagship
 * @Date 2021/3/28 15:48
 * @Description ??????Service?????????
 */
@Service("orderService")
public class OrderServiceImpl implements OrderService {
    @Resource
    OrderMapper orderMapper;
    @Resource
    CartService cartService;
    @Resource
    UserFeignClient userFeignClient;
    @Resource
    ProductFeignClient productFeignClient;
    @Resource
    CartMapper cartMapper;
    @Resource
    OrderItemMapper orderItemMapper;
    @Value("${file.upload.ip}")
    String ip;
    @Value("${file.upload.port}")
    Integer port;
    @Value("${file.upload.dir}")
    String FILE_UPLOAD_DIR;

    /**
     * ????????????
     * @param userId ??????id
     * @param createOrderReq ????????????
     * @return ?????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(Integer userId, CreateOrderReq createOrderReq) {
        //????????????????????????????????????????????????
        List<CartVO> cartVOList = cartService.list(userId);
        ArrayList<CartVO> cartVOListChecked = new ArrayList<>();
        for (CartVO cartVO : cartVOList) {
            if (cartVO.getSelected().equals(Constant.CartSelected.CHECKED)) {
                cartVOListChecked.add(cartVO);
            }
        }
        if (CollectionUtils.isEmpty(cartVOListChecked)) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.CART_SELECTED_EMPTY);
        }
        //???????????????????????????????????????????????????
        validSaleStatusAndStock(cartVOListChecked);
        //??????????????????????????????item??????
        List<OrderItem> orderItemList = cartVOListToOrderItemList(cartVOListChecked);
        //?????????
        for (OrderItem orderItem : orderItemList) {
            Product product = productFeignClient.detailForFeign(orderItem.getProductId());
            int stock = product.getStock() - orderItem.getQuantity();
            if (stock < 0) {
                throw new FlagshipMallException(FlagshipMallExceptionEnum.NOT_ENOUGH);
            }
            productFeignClient.updateStock(product.getId(), stock);
        }
        //????????????????????????????????????
        cleanCart(cartVOListChecked);
        //????????????????????????
        Order order = new Order();
        String orderNo = OrderCodeFactory.getOrderCode(Long.valueOf(userId));
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setTotalPrice(totalPrice(orderItemList));
        order.setReceiverName(createOrderReq.getReceiverName());
        order.setReceiverMobile(createOrderReq.getReceiverMobile());
        order.setReceiverAddress(createOrderReq.getReceiverAddress());
        order.setOrderStatus(Constant.OrderStatusEnum.NOT_PAID.getCode());
        order.setPostage(BigDecimal.ZERO);
        order.setPaymentType(1);
        //?????????Order???
        if (orderMapper.insertSelective(order) == 0) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.CREATE_FAILED);
        }
        //???????????????????????????order_item???
        for (OrderItem orderItem : orderItemList) {
            orderItem.setOrderNo(order.getOrderNo());
            int count = orderItemMapper.insertSelective(orderItem);
            if (count == 0) {
                throw new FlagshipMallException(FlagshipMallExceptionEnum.CREATE_FAILED);
            }
        }
        return orderNo;
    }

    /**
     * ???????????????????????????
     * @param orderItemList ???????????????
     * @return ?????????
     */
    private BigDecimal totalPrice(List<OrderItem> orderItemList) {
        BigDecimal totalPrice = BigDecimal.ZERO;
        for (OrderItem orderItem : orderItemList) {
            totalPrice = totalPrice.add(orderItem.getTotalPrice());
        }
        return totalPrice;
    }

    private void cleanCart(List<CartVO> cartVOList) {
        for (CartVO cartVO : cartVOList) {
            cartMapper.deleteByPrimaryKey(cartVO.getId());
        }
    }

    /**
     * ??????????????????????????????????????????????????????
     * @param cartVOListChecked ?????????????????????????????????
     * @return ???????????????
     */
    private List<OrderItem> cartVOListToOrderItemList(ArrayList<CartVO> cartVOListChecked) {
        ArrayList<OrderItem> orderItemList = new ArrayList<>();
        for (CartVO cartVO : cartVOListChecked) {
            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(cartVO.getProductId());
            //????????????????????????
            orderItem.setProductName(cartVO.getProductName());
            orderItem.setProductImg(cartVO.getProductImage());
            orderItem.setUnitPrice(cartVO.getPrice());
            orderItem.setQuantity(cartVO.getQuantity());
            orderItem.setTotalPrice(cartVO.getTotalPrice());
            orderItemList.add(orderItem);
        }
        return orderItemList;
    }

    /**
     * ????????????????????????????????????????????????
     * @param cartVOList ???????????????
     */
    private void validSaleStatusAndStock(List<CartVO> cartVOList) {
        for (CartVO cartVO : cartVOList) {
            Product product = productFeignClient.detailForFeign(cartVO.getProductId());
            //???????????????????????? ????????????
            if (product == null || product.getStatus().equals(Constant.ProductSaleStatus.NOT_SALE)) {
                throw new FlagshipMallException(FlagshipMallExceptionEnum.NOT_SALE);
            }
            //??????????????????
            if (cartVO.getQuantity() > product.getStock()) {
                throw new FlagshipMallException(FlagshipMallExceptionEnum.NOT_ENOUGH);
            }
        }
    }

    /**
     * ??????????????????
     * @param orderNo ?????????
     * @param userId ??????id
     * @return ??????????????????
     */
    @Override
    public OrderVO detail(String orderNo, Integer userId) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        //????????????????????????
        if (order == null) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.NO_ORDER);
        }
        //??????????????????
        if (!order.getUserId().equals(userId)) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.NOT_YOUR_ORDER);
        }
        OrderVO orderVO = getOrderVO(order);
        return orderVO;
    }

    /**
     * ????????????????????????????????????
     * @param order ??????
     * @return ??????????????????
     */
    private OrderVO getOrderVO(Order order) {
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order, orderVO);
        //???????????????OrderItemVOList
        List<OrderItem> orderItemList = orderItemMapper.selectByOrderNo(order.getOrderNo());
        ArrayList<OrderItemVO> orderItemVOList = new ArrayList<>();
        for (OrderItem orderItem : orderItemList) {
            OrderItemVO orderItemVO = new OrderItemVO();
            BeanUtils.copyProperties(orderItem, orderItemVO);
            orderItemVOList.add(orderItemVO);
        }
        orderVO.setOrderItemVOList(orderItemVOList);
        orderVO.setOrderStatusName(Constant.OrderStatusEnum.codeOf(orderVO.getOrderStatus()).getValue());
        return orderVO;
    }

    /**
     * ??????????????????????????????
     * @param userId ??????id
     * @param pageNum ??????
     * @param pageSize ???????????????
     * @return ????????????
     */
    @Override
    public PageInfo listForCustomer(Integer userId, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Order> orderList = orderMapper.selectForCustomer(userId);
        List<OrderVO> orderVOList = orderListToOrderVOList(orderList);
        PageInfo pageInfo = new PageInfo<>(orderVOList);
        pageInfo.setList(orderVOList);
        return pageInfo;
    }

    /**
     * ??????????????????????????????VOList
     * @param orderList ????????????
     * @return ??????VOList
     */
    private List<OrderVO> orderListToOrderVOList(List<Order> orderList) {
        List<OrderVO> orderVOList = new ArrayList<>();
        for (Order order : orderList) {
            OrderVO orderVO = getOrderVO(order);
            orderVOList.add(orderVO);
        }
        return orderVOList;
    }

    /**
     * ????????????
     * @param userId ??????id
     * @param orderNo ?????????
     */
    @Override
    public void cancel(Integer userId, String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.NO_ORDER);
        }
        if (!order.getUserId().equals(userId)) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.NOT_YOUR_ORDER);
        }
        if (!order.getOrderStatus().equals(Constant.OrderStatusEnum.NOT_PAID.getCode())) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.WRONG_ORDER_STATUS);
        }
        order.setOrderStatus(Constant.OrderStatusEnum.CANCELED.getCode());
        order.setEndTime(new Date());
        int count = orderMapper.updateByPrimaryKeySelective(order);
        if (count == 0) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.UPDATE_FAILED);
        }
    }

    /**
     * ?????????????????????
     * @param orderNo ????????????
     * @return ???????????????
     */
    @Override
    public String qrcode(String orderNo) {
        String address = ip + ":" + port;
        String payUrl = "http://" + address + "/cart-order/order/pay?orderNo=" + orderNo;
        try {
            QrCodeGenerator.generateQrCodeImage(payUrl, 350, 350, FILE_UPLOAD_DIR + orderNo + ".png");
        } catch (WriterException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        String pngAddress = "http://" + address + "/cart-order/images/" + orderNo + ".png";
        return pngAddress;
    }

    /**
     * ?????????????????????????????????
     * @param pageNum ??????
     * @param pageSize ???????????????
     * @return ????????????
     */
    @Override
    public PageInfo listForAdmin(Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Order> orderList = orderMapper.selectAllForAdmin();
        List<OrderVO> orderVOList = orderListToOrderVOList(orderList);
        PageInfo pageInfo = new PageInfo<>(orderVOList);
        pageInfo.setList(orderVOList);
        return pageInfo;
    }

    /**
     * ????????????
     * @param orderNo ?????????
     */
    @Override
    public void pay(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.NO_ORDER);
        }
        if (!order.getOrderStatus().equals(Constant.OrderStatusEnum.NOT_PAID.getCode())) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.WRONG_ORDER_STATUS);
        }
        order.setOrderStatus(Constant.OrderStatusEnum.PAID.getCode());
        order.setPayTime(new Date());
        orderMapper.updateByPrimaryKeySelective(order);
    }

    /**
     * ????????????
     * @param orderNo ?????????
     */
    @Override
    public void deliver(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.NO_ORDER);
        }
        if (!order.getOrderStatus().equals(Constant.OrderStatusEnum.PAID.getCode())) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.WRONG_ORDER_STATUS);
        }
        order.setOrderStatus(Constant.OrderStatusEnum.DELIVERED.getCode());
        order.setDeliveryTime(new Date());
        orderMapper.updateByPrimaryKeySelective(order);
    }

    /**
     * ????????????
     *
     * @param orderNo ?????????
     */
    @Override
    public void finish(String orderNo, User currentUser) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.NO_ORDER);
        }
        //????????????????????????????????????????????????
        User user = userFeignClient.getUser();
        if (user.getRole().equals(1) && !order.getUserId().equals(user.getId())) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.NOT_YOUR_ORDER);
        }
        if (!order.getOrderStatus().equals(Constant.OrderStatusEnum.DELIVERED.getCode())) {
            throw new FlagshipMallException(FlagshipMallExceptionEnum.WRONG_ORDER_STATUS);
        }
        order.setOrderStatus(Constant.OrderStatusEnum.FINISHED.getCode());
        order.setEndTime(new Date());
        orderMapper.updateByPrimaryKeySelective(order);
    }
}
