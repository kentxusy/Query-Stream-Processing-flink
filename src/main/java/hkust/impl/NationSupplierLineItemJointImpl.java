package hkust.impl;

import hkust.schema.LineItem;
import hkust.schema.NationSupplierJointSchema;
import hkust.schema.NationSupplierLineItemJointSchema;
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

public class NationSupplierLineItemJointImpl extends CoProcessFunction<NationSupplierJointSchema, LineItem, NationSupplierLineItemJointSchema> {
        // Define state
    MapState<Integer, NationSupplierJointSchema> nationSupplierJointIL; // Stores alive tuples of nation customer joint relation
    MapState<Integer, List<Tuple>> lineItemIRRc; // I(R, Rc) of order stream
    MapState<Tuple, LineItem> lineItemIR; // All tuples I(R) in lineItem
    MapState<Tuple, LineItem> lineItemIN; // Non-active tuples I(N(R)) in lineItem
    MapState<Tuple, LineItem> lineItemIL; // Active tuples I(L(R)) in lineItem
    MapState<Tuple, Integer> lineItemCounter; // Counter count s(t) in lineItem

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        nationSupplierJointIL = getRuntimeContext().getMapState(new MapStateDescriptor<>("nationSupplierJointIL", Types.INT, TypeInformation.of(NationSupplierJointSchema.class)));
        lineItemIRRc = getRuntimeContext().getMapState(new MapStateDescriptor<>("lineItemIRRc", Types.INT, Types.LIST(Types.TUPLE())));
        lineItemIR = getRuntimeContext().getMapState(new MapStateDescriptor<>("lineItemIR", Types.TUPLE(), TypeInformation.of(LineItem.class)));
        lineItemIL = getRuntimeContext().getMapState(new MapStateDescriptor<>("lineItemIL", Types.TUPLE(), TypeInformation.of(LineItem.class)));
        lineItemIN = getRuntimeContext().getMapState(new MapStateDescriptor<>("lineItemIN", Types.TUPLE(), TypeInformation.of(LineItem.class)));
        lineItemCounter = getRuntimeContext().getMapState(new MapStateDescriptor<>("lineItemCounter", Types.TUPLE(), Types.INT));
    }

    // Process child relation
    @Override
    public void processElement1(NationSupplierJointSchema nationSupplierJointValue, CoProcessFunction<NationSupplierJointSchema, LineItem, NationSupplierLineItemJointSchema>.Context context, Collector<NationSupplierLineItemJointSchema> collector) throws Exception {
        Integer suppKey = nationSupplierJointValue.getS_suppKey();

        // Update live tuple for nation supplier joint relation
        nationSupplierJointIL.put(suppKey, nationSupplierJointValue);

        // Update parent relation's counter and I(L(R)) using I(R, Rc)
        List<Tuple> lineItemTuples = lineItemIRRc.get(suppKey);
        if (lineItemTuples != null) {
            for (Tuple tuple : lineItemTuples) {
                Tuple key = tuple.getField(0);
                Integer num = lineItemCounter.get(key);
                // Update parent relation's counter
                if (num == null || num < 1) {
                    lineItemCounter.put(key, 1);
                }

                // Update parent relation's I(L(R))
                if (!lineItemIL.contains(key)){
                    lineItemIL.put(key, lineItemIR.get(key));
                }

                // Parent JOIN child relation using I(R, Rc)
                LineItem lineItem = lineItemIL.get(key);
                String flag = (nationSupplierJointValue.getFlag().equals("+") && lineItem.getFlag().equals("+")) ? "+" : "-";
                NationSupplierLineItemJointSchema nationSupplierLineItemJointValue= new NationSupplierLineItemJointSchema(
                        flag,
                        lineItem.getL_suppKey(),
                        lineItem.getL_orderKey(),
                        lineItem.getL_lineNumber(),
                        lineItem.getL_shipDate(),
                        lineItem.getL_extendedPrice(),
                        lineItem.getL_discount(),
                        nationSupplierJointValue.getS_suppKey(),
                        nationSupplierJointValue.getN_nationKey(),
                        nationSupplierJointValue.getN_name()
                );
                collector.collect(nationSupplierLineItemJointValue);
            }
        }
    }

    // Process parent relation
    @Override
    public void processElement2(LineItem lineItem, CoProcessFunction<NationSupplierJointSchema, LineItem, NationSupplierLineItemJointSchema>.Context context, Collector<NationSupplierLineItemJointSchema> collector) throws Exception {
        Tuple parentKey = Tuple2.of(lineItem.getL_orderKey(), lineItem.getL_lineNumber());
        Integer suppKey = lineItem.getL_suppKey();

        // Update live tuple for lineItem
        lineItemIR.put(parentKey, lineItem);

        // Initialize lineItem counter
        lineItemCounter.put(parentKey, 0);

        // Update I(R, Rc)
        List<Tuple> list = lineItemIRRc.get(suppKey);
        if (list == null) {
            list = new ArrayList<>();
        }
        Tuple tuple = Tuple2.of(parentKey, suppKey);
        if (!list.contains(tuple)) {
            list.add(tuple);
            lineItemIRRc.put(suppKey, list);
        }

        // Update counter s(t)
        if (nationSupplierJointIL.contains(suppKey)) {
            lineItemCounter.put(parentKey, 1);
        }

        // Update I(L(R)) and I(N(R))
        if (lineItemCounter.get(parentKey) == 1) {
            lineItemIL.put(parentKey, lineItem);
        } else {
            lineItemIN.put(parentKey, lineItem);
        }

        // Parent relation join child relation
        if (lineItemIL.contains(parentKey)) {
            NationSupplierJointSchema nationSupplierJointValue = nationSupplierJointIL.get(suppKey);
            String flag = (nationSupplierJointValue.getFlag().equals("+") && lineItem.getFlag().equals("+")) ? "+" : "-";
            NationSupplierLineItemJointSchema nationSupplierLineItemJointValue = new NationSupplierLineItemJointSchema(
                    flag,
                    lineItem.getL_suppKey(),
                    lineItem.getL_orderKey(),
                    lineItem.getL_lineNumber(),
                    lineItem.getL_shipDate(),
                    lineItem.getL_extendedPrice(),
                    lineItem.getL_discount(),
                    nationSupplierJointValue.getS_suppKey(),
                    nationSupplierJointValue.getN_nationKey(),
                    nationSupplierJointValue.getN_name()
            );
            collector.collect(nationSupplierLineItemJointValue);
        }
    }
}
