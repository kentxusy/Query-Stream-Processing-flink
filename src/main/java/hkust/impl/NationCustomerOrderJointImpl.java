package hkust.impl;

import hkust.schema.NationCustomerOrderJointSchema;
import hkust.schema.NationCustomerJointSchema;
import hkust.schema.Order;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.CoProcessFunction;
import org.apache.flink.util.Collector;


import java.util.ArrayList;
import java.util.List;

public class NationCustomerOrderJointImpl extends CoProcessFunction<NationCustomerJointSchema, Order, NationCustomerOrderJointSchema> {
    // Define state
    MapState<Integer, NationCustomerJointSchema> nationCustomerJointIL; // Stores alive tuples of nation customer joint relation
    MapState<Integer, List<Tuple>> orderIRRc; // I(R, Rc) of order stream
    MapState<Integer, Order> orderIR; // All tuples I(R) in order
    MapState<Integer, Order> orderIN; // Non-active tuples I(N(R))
    MapState<Integer, Order> orderIL; // Active tuples I(L(R))
    MapState<Integer, Integer> orderCounter; // Counter count s(t)

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        nationCustomerJointIL = getRuntimeContext().getMapState(new MapStateDescriptor<>("nationCustomerJointIL", Types.INT, TypeInformation.of(NationCustomerJointSchema.class)));
        orderIRRc = getRuntimeContext().getMapState(new MapStateDescriptor<>("orderIRRc", Types.INT, Types.LIST(Types.TUPLE())));
        orderIR = getRuntimeContext().getMapState(new MapStateDescriptor<>("orderIR", Types.INT, TypeInformation.of(Order.class)));
        orderIL = getRuntimeContext().getMapState(new MapStateDescriptor<>("orderIL", Types.INT, TypeInformation.of(Order.class)));
        orderIN = getRuntimeContext().getMapState(new MapStateDescriptor<>("orderIN", Types.INT, TypeInformation.of(Order.class)));
        orderCounter = getRuntimeContext().getMapState(new MapStateDescriptor<>("orderCounter", Types.INT, Types.INT));
    }

    // Process child relation
    @Override
    public void processElement1(NationCustomerJointSchema nationCustomerJointValue, CoProcessFunction<NationCustomerJointSchema, Order, NationCustomerOrderJointSchema>.Context context, Collector<NationCustomerOrderJointSchema> collector) throws Exception {
        Integer custKey = nationCustomerJointValue.getC_custKey();

        // Update live tuple for nation customer joint relation
        nationCustomerJointIL.put(custKey, nationCustomerJointValue);

        // Update parent relation's counter and I(L(R)) using I(R, Rc)
        List<Tuple> orderTuples = orderIRRc.get(custKey);
        if (orderTuples != null) {
            for (Tuple tuple : orderTuples) {
                Integer key = tuple.getField(0);
                Integer num = orderCounter.get(key);
                // Update parent relation's counter
                if (num == null || num < 1) {
                    orderCounter.put(key, 1);
                }

                // Update parent relation's I(L(R))
                if (!orderIL.contains(key)){
                    orderIL.put(key, orderIR.get(key));
                }

                // Parent JOIN child relation using I(R, Rc)
                Order order = orderIL.get(key);
                String flag = (nationCustomerJointValue.getFlag().equals("+") && order.getFlag().equals("+")) ? "+" : "-";
                NationCustomerOrderJointSchema nationCustomerOrderJointValue= new NationCustomerOrderJointSchema(
                        flag,
                        nationCustomerJointValue.getC_custKey(),
                        nationCustomerJointValue.getC_nationKey(),
                        nationCustomerJointValue.getN_nationKey(),
                        nationCustomerJointValue.getN_name(),
                        order.getO_orderKey(),
                        order.getO_custKey()
                );
                collector.collect(nationCustomerOrderJointValue);
            }
        }
    }

    // Process parent relation
    @Override
    public void processElement2(Order order, CoProcessFunction<NationCustomerJointSchema, Order, NationCustomerOrderJointSchema>.Context context, Collector<NationCustomerOrderJointSchema> collector) throws Exception {
        Integer orderKey = order.getO_orderKey();
        Integer custKey = order.getO_custKey();

        // Update live tuple for order
        orderIR.put(orderKey, order);

        // Initialize order counter
        orderCounter.put(orderKey, 0);

        // Update I(R, Rc)
        List<Tuple> list = orderIRRc.get(custKey);
        if (list == null) {
            list = new ArrayList<>();
        }
        Tuple tuple = Tuple2.of(orderKey, custKey);
        if (!list.contains(tuple)){
            list.add(tuple);
            orderIRRc.put(custKey, list);
        }

        // Update counter s(t)
        if (nationCustomerJointIL.contains(custKey)){
            orderCounter.put(orderKey, 1);
        }

        // Update I(L(R)) and I(N(R))
        if (orderCounter.get(orderKey) == 1){
            orderIL.put(orderKey, order);
        } else{
            orderIN.put(orderKey, order);
        }

        // Parent relation join child relation
        if (orderIL.contains(orderKey)){
            NationCustomerJointSchema nationCustomerJointValue = nationCustomerJointIL.get(custKey);
            String flag = (nationCustomerJointValue.getFlag().equals("+") && order.getFlag().equals("+")) ? "+" : "-";
            NationCustomerOrderJointSchema nationCustomerOrderJointValue = new NationCustomerOrderJointSchema(
                    flag,
                    nationCustomerJointValue.getC_custKey(),
                    nationCustomerJointValue.getC_nationKey(),
                    nationCustomerJointValue.getN_nationKey(),
                    nationCustomerJointValue.getN_name(),
                    order.getO_orderKey(),
                    order.getO_custKey()
            );
            collector.collect(nationCustomerOrderJointValue);
        }
    }

}
