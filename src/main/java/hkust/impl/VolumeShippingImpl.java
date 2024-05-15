package hkust.impl;

import hkust.schema.NationCustomerOrderJointSchema;
import hkust.schema.NationSupplierLineItemJointSchema;
import hkust.schema.VolumeShippingResult;
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

/**
 * This is used to connect nation customer order joint result with nation supplier lineItem joint result
 * to get the final volume shipping result.
 */
public class VolumeShippingImpl extends CoProcessFunction<NationCustomerOrderJointSchema, NationSupplierLineItemJointSchema, VolumeShippingResult> {
    // Define state
    MapState<Integer, NationCustomerOrderJointSchema> nationCustomerOrderJointIL; // Stores alive tuples of nation customer order joint relation
    MapState<Integer, List<Tuple>> nationSupplierLineItemJointIRRC; // I(R, Rc) of nation supplier lineItem joint relation
    MapState<Tuple, NationSupplierLineItemJointSchema> nationSupplierLineItemJointIR; // All tuples I(R) in nation supplier lineItem joint relation
    MapState<Tuple, NationSupplierLineItemJointSchema> nationSupplierLineItemJointIN; // Non-active tuples I(N(R)) in nation supplier lineItem joint relation
    MapState<Tuple, NationSupplierLineItemJointSchema> nationSupplierLineItemJointIL; // Active tuples I(L(R)) in nation supplier lineItem joint relation
    MapState<Tuple, Integer> nationSupplierLineItemJointCounter; // Counter count s(t) of nation supplier lineItem joint relation

    // Initialization
    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        nationCustomerOrderJointIL = getRuntimeContext().getMapState(new MapStateDescriptor<>("nationCustomerOrderJointIL", Types.INT, TypeInformation.of(NationCustomerOrderJointSchema.class)));
        nationSupplierLineItemJointIRRC = getRuntimeContext().getMapState(new MapStateDescriptor<>("nationSupplierLineItemJointIRRC", Types.INT, Types.LIST(Types.TUPLE())));
        nationSupplierLineItemJointIR = getRuntimeContext().getMapState(new MapStateDescriptor<>("nationSupplierLineItemJointIR", Types.TUPLE(), TypeInformation.of(NationSupplierLineItemJointSchema.class)));
        nationSupplierLineItemJointIL = getRuntimeContext().getMapState(new MapStateDescriptor<>("nationSupplierLineItemJointIL", Types.TUPLE(), TypeInformation.of(NationSupplierLineItemJointSchema.class)));
        nationSupplierLineItemJointIN = getRuntimeContext().getMapState(new MapStateDescriptor<>("nationSupplierLineItemJointIN", Types.TUPLE(), TypeInformation.of(NationSupplierLineItemJointSchema.class)));
        nationSupplierLineItemJointCounter = getRuntimeContext().getMapState(new MapStateDescriptor<>("nationSupplierLineItemJointCounter", Types.TUPLE(), Types.INT));
    }

    // Process child relation
    @Override
    public void processElement1(NationCustomerOrderJointSchema nationCustomerOrderJointValue, CoProcessFunction<NationCustomerOrderJointSchema, NationSupplierLineItemJointSchema, VolumeShippingResult>.Context context, Collector<VolumeShippingResult> collector) throws Exception {
        Integer orderKey = nationCustomerOrderJointValue.getO_orderKey();

        // Update live tuple for nation customer order joint relation
        nationCustomerOrderJointIL.put(orderKey, nationCustomerOrderJointValue);

        // Update parent relation's counter and I(L(R)) using I(R, Rc)
        List<Tuple> nationSupplierLineItemJointTuples = nationSupplierLineItemJointIRRC.get(orderKey);
        if (nationSupplierLineItemJointTuples != null) {
            for (Tuple tuple : nationSupplierLineItemJointTuples) {
                Tuple key = tuple.getField(0);
                Integer num = nationSupplierLineItemJointCounter.get(key);
                // Update parent relation's counter
                if (num == null || num < 1) {
                    nationSupplierLineItemJointCounter.put(key, 1);
                }

                // Update parent relation's I(L(R))
                if (!nationSupplierLineItemJointIL.contains(key)){
                    nationSupplierLineItemJointIL.put(key, nationSupplierLineItemJointIR.get(key));
                }

                // Parent JOIN child relation using I(R, Rc)
                NationSupplierLineItemJointSchema nationSupplierLineItemJointValue = nationSupplierLineItemJointIL.get(key);
                if (getFilteredData(nationSupplierLineItemJointValue.getN_name(), nationCustomerOrderJointValue.getN_name())) {
                    String flag = (nationCustomerOrderJointValue.getFlag().equals("+") && nationSupplierLineItemJointValue.getFlag().equals("+")) ? "+" : "-";
                    Double volume = nationSupplierLineItemJointValue.getL_extendedPrice() * (1 - nationSupplierLineItemJointValue.getL_discount());
                    VolumeShippingResult volumeShippingResult = new VolumeShippingResult(
                            flag,
                            nationSupplierLineItemJointValue.getL_orderKey(),
                            nationSupplierLineItemJointValue.getL_lineNumber(),
                            nationSupplierLineItemJointValue.getN_name(),
                            nationCustomerOrderJointValue.getN_name(),
                            nationSupplierLineItemJointValue.getL_shipDate().getYear(),
                            volume
                    );
                    collector.collect(volumeShippingResult);
                }

            }
        }
    }

    // Process parent relation
    @Override
    public void processElement2(NationSupplierLineItemJointSchema nationSupplierLineItemJointValue, CoProcessFunction<NationCustomerOrderJointSchema, NationSupplierLineItemJointSchema, VolumeShippingResult>.Context context, Collector<VolumeShippingResult> collector) throws Exception {
        Tuple parentKey = Tuple2.of(nationSupplierLineItemJointValue.getL_orderKey(), nationSupplierLineItemJointValue.getL_lineNumber());
        Integer orderKey = nationSupplierLineItemJointValue.getL_orderKey();

        // Update live tuple for nation supplier lineItem joint relation
        nationSupplierLineItemJointIR.put(parentKey, nationSupplierLineItemJointValue);


        // Initialize counter of nation supplier lineItem joint relation
        nationSupplierLineItemJointCounter.put(parentKey, 0);

        // Update I(R, Rc)
        List<Tuple> list = nationSupplierLineItemJointIRRC.get(orderKey);
        if (list == null) {
            list = new ArrayList<>();
        }
        Tuple tuple = Tuple2.of(parentKey, orderKey);
        if (!list.contains(tuple)){
            list.add(tuple);
            nationSupplierLineItemJointIRRC.put(orderKey, list);
        }


        // Update counter s(t)
        if (nationCustomerOrderJointIL.contains(orderKey)){
            nationSupplierLineItemJointCounter.put(parentKey, 1);
        }

        // Update I(L(R)) and I(N(R))
        if (nationSupplierLineItemJointCounter.get(parentKey) == 1){
            nationSupplierLineItemJointIL.put(parentKey, nationSupplierLineItemJointValue);
        } else{
            nationSupplierLineItemJointIN.put(parentKey, nationSupplierLineItemJointValue);
        }

        // Parent relation join child relation
        if (nationSupplierLineItemJointIL.contains(parentKey)){
            NationCustomerOrderJointSchema nationCustomerOrderJointValue = nationCustomerOrderJointIL.get(orderKey);
            if (getFilteredData(nationSupplierLineItemJointValue.getN_name(), nationCustomerOrderJointValue.getN_name())) {
                String flag = (nationCustomerOrderJointValue.getFlag().equals("+") && nationSupplierLineItemJointValue.getFlag().equals("+")) ? "+" : "-";
                Double volume = nationSupplierLineItemJointValue.getL_extendedPrice() * (1 - nationSupplierLineItemJointValue.getL_discount());
                VolumeShippingResult volumeShippingResult = new VolumeShippingResult(
                        flag,
                        nationSupplierLineItemJointValue.getL_orderKey(),
                        nationSupplierLineItemJointValue.getL_lineNumber(),
                        nationSupplierLineItemJointValue.getN_name(),
                        nationCustomerOrderJointValue.getN_name(),
                        nationSupplierLineItemJointValue.getL_shipDate().getYear(),
                        volume
                );
                collector.collect(volumeShippingResult);
            }
        }
    }

    private boolean getFilteredData(String n1_name, String n2_name) {
        return (n1_name.equals("ALGERIA") && n2_name.equals("BRAZIL")) ||
                (n1_name.equals("BRAZIL") && n2_name.equals("ALGERIA"));
    }
}
