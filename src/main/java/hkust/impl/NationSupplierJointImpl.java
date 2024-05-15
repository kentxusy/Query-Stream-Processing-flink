package hkust.impl;

import hkust.schema.Nation;
import hkust.schema.NationSupplierJointSchema;
import hkust.schema.Supplier;
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

public class NationSupplierJointImpl extends CoProcessFunction<Nation, Supplier, NationSupplierJointSchema> {
    // Define state
    MapState<Integer, Nation> nationNewIL; // Stores alive tuples of nation stream
    MapState<Integer, List<Tuple>> supplierIRRC; // I(R, Rc) of supplier stream
    MapState<Integer, Supplier> supplierIR; // All tuples I(R) in supplier
    MapState<Integer, Supplier> supplierIN; // Non-active tuples I(N(R))
    MapState<Integer, Supplier> supplierIL; // Active tuples I(L(R))
    MapState<Integer, Integer> supplierCounter; // Counter count s(t)

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        nationNewIL = getRuntimeContext().getMapState(new MapStateDescriptor<>("nationNewIL", Types.INT, TypeInformation.of(Nation.class)));
        supplierIRRC = getRuntimeContext().getMapState(new MapStateDescriptor<>("supplierIRRC", Types.INT, Types.LIST(Types.TUPLE())));
        supplierIR = getRuntimeContext().getMapState(new MapStateDescriptor<>("supplierIR", Types.INT, TypeInformation.of(Supplier.class)));
        supplierIL = getRuntimeContext().getMapState(new MapStateDescriptor<>("supplierIL", Types.INT, TypeInformation.of(Supplier.class)));
        supplierIN = getRuntimeContext().getMapState(new MapStateDescriptor<>("supplierIN", Types.INT, TypeInformation.of(Supplier.class)));
        supplierCounter = getRuntimeContext().getMapState(new MapStateDescriptor<>("supplierCounter", Types.INT, Types.INT));
    }

    // Process child relation
    @Override
    public void processElement1(Nation nation, CoProcessFunction<Nation, Supplier, NationSupplierJointSchema>.Context context, Collector<NationSupplierJointSchema> collector) throws Exception {
        Integer nationKey = nation.getN_nationKey();

        // Update live tuple for nation relation
        nationNewIL.put(nationKey, nation);

        // Update parent relation's counter and I(L(R)) using I(R, Rc)
        List<Tuple> supplierTuples = supplierIRRC.get(nationKey);
        if (supplierTuples != null) {
            for (Tuple tuple : supplierTuples) {
                Integer key = tuple.getField(0);
                Integer num = supplierCounter.get(key);
                // Update parent relation's counter
                if (num == null || num < 1) {
                    supplierCounter.put(key, 1);
                }

                // Update parent relation's I(L(R))
                if (!supplierIL.contains(key)){
                    supplierIL.put(key, supplierIR.get(key));
                }

                // Parent JOIN child relation using I(R, Rc)
                Supplier supplier = supplierIL.get(key);
                String flag = (nation.getFlag().equals("+") && supplier.getFlag().equals("+")) ? "+" : "-";
                NationSupplierJointSchema NationSupplierJointValue= new NationSupplierJointSchema(
                        flag,
                        supplier.getS_suppKey(),
                        supplier.getS_nationKey(),
                        nation.getN_nationKey(),
                        nation.getN_name()
                );
                collector.collect(NationSupplierJointValue);
            }
        }
    }

    // Process parent relation
    @Override
    public void processElement2(Supplier supplier, CoProcessFunction<Nation, Supplier, NationSupplierJointSchema>.Context context, Collector<NationSupplierJointSchema> collector) throws Exception {
        Integer supplierKey = supplier.getS_suppKey();
        Integer nationKey = supplier.getS_nationKey();

        // Update live tuple for supplier
        supplierIR.put(supplierKey, supplier);

        // Initialize supplier counter
        supplierCounter.put(supplierKey, 0);

        // Update I(R, Rc)
        List<Tuple> list = supplierIRRC.get(nationKey);
        if (list == null) {
            list = new ArrayList<>();
        }
        Tuple tuple = Tuple2.of(supplierKey, nationKey);
        if (!list.contains(tuple)){
            list.add(tuple);
            supplierIRRC.put(nationKey, list);
        }

        // Update counter s(t)
        if (nationNewIL.contains(nationKey)){
            supplierCounter.put(supplierKey, 1);
        }

        // Update I(L(R)) and I(N(R))
        if (supplierCounter.get(supplierKey) == 1){
            supplierIL.put(supplierKey, supplier);
        } else{
            supplierIN.put(supplierKey, supplier);
        }

        // Parent relation join child relation
        if (supplierIL.contains(supplierKey)){
            Nation nation = nationNewIL.get(nationKey);
            String flag = (nation.getFlag().equals("+") && supplier.getFlag().equals("+")) ? "+" : "-";
            NationSupplierJointSchema nationSupplierJointValue = new NationSupplierJointSchema(
                    flag,
                    supplier.getS_suppKey(),
                    supplier.getS_nationKey(),
                    nation.getN_nationKey(),
                    nation.getN_name()
            );
            collector.collect(nationSupplierJointValue);
        }
    }

}
