package hkust.impl;

import hkust.schema.Customer;
import hkust.schema.Nation;
import hkust.schema.NationCustomerJointSchema;
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

public class NationCustomerJointImpl extends CoProcessFunction<Nation, Customer, NationCustomerJointSchema> {
    // Define state
    MapState<Integer, Nation> nationIL; // Stores alive tuples of nation stream
    MapState<Integer, List<Tuple>> customerIRRc; // I(R, Rc) of customer stream
    MapState<Integer, Customer> customerIR; // All tuples I(R) in customer
    MapState<Integer, Customer> customerIN; // Non-active tuples I(N(R))
    MapState<Integer, Customer> customerIL; // Active tuples I(L(R))
    MapState<Integer, Integer> customerCounter; // Counter count s(t)

    // State Initialization
    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        nationIL = getRuntimeContext().getMapState(new MapStateDescriptor<>("nationIL", TypeInformation.of(Integer.class), TypeInformation.of(Nation.class)));
        customerIR = getRuntimeContext().getMapState(new MapStateDescriptor<>("customerIR", TypeInformation.of(Integer.class), TypeInformation.of(Customer.class)));
        customerIL = getRuntimeContext().getMapState(new MapStateDescriptor<>("customerIL", TypeInformation.of(Integer.class), TypeInformation.of(Customer.class)));
        customerIN = getRuntimeContext().getMapState(new MapStateDescriptor<>("customerIN", TypeInformation.of(Integer.class), TypeInformation.of(Customer.class)));
        customerCounter = getRuntimeContext().getMapState(new MapStateDescriptor<>("customerCounter", Types.INT, Types.INT));
        customerIRRc = getRuntimeContext().getMapState(new MapStateDescriptor<>("customerIRRc", Types.INT, Types.LIST(Types.TUPLE())));
    }

    // Child relation(nation)
    @Override
    public void processElement1(Nation nation, CoProcessFunction<Nation, Customer, NationCustomerJointSchema>.Context context, Collector<NationCustomerJointSchema> collector) throws Exception {
        Integer nationKey = nation.getN_nationKey();

        // Update live tuple for nation
        nationIL.put(nationKey, nation);

        // Update parent relation's counter and I(L(R)) using I(R, Rc)
        List<Tuple> customerTuples = customerIRRc.get(nationKey);

        if (customerTuples != null){
            for (Tuple tuple : customerTuples){
                Integer key = tuple.getField(0);

                // Update parent relation's counter
                Integer num = customerCounter.get(key);
                if (num == null || num < 1) {
                    customerCounter.put(key, 1);
                }

                // Update parent relation's I(L(R))
                if (!customerIL.contains(key)){
                    customerIL.put(key, customerIR.get(key));
                }

                // Parent JOIN child relation using I(R, Rc)
                Customer customer = customerIL.get(key);
                String flag = (nation.getFlag().equals("+") && customer.getFlag().equals("+")) ? "+" : "-";

                NationCustomerJointSchema nationCustomerJointValue = new NationCustomerJointSchema(
                        flag,
                        customer.getC_custKey(),
                        customer.getC_nationKey(),
                        nation.getN_nationKey(),
                        nation.getN_name()
                );
                collector.collect(nationCustomerJointValue);
            }
        }
    }

    // Process parent relation
    @Override
    public void processElement2(Customer customer, CoProcessFunction<Nation, Customer, NationCustomerJointSchema>.Context context, Collector<NationCustomerJointSchema> collector) throws Exception {
        Integer customerKey = customer.getC_custKey();
        Integer nationKey = customer.getC_nationKey();

        // Update live tuple for customer
        customerIR.put(customerKey, customer);

        // Initialize customer counter
        customerCounter.put(customerKey, 0);

        // Update I(R, Rc)
        List<Tuple> list = customerIRRc.get(nationKey);
        if (list == null) {
            list = new ArrayList<>();
        }
        Tuple tuple = Tuple2.of(customerKey, nationKey);
        if (!list.contains(tuple)){
            list.add(tuple);
            customerIRRc.put(nationKey, list);
        }

        // Update counter s(t)
        if (nationIL.contains(nationKey)){
            customerCounter.put(customerKey, 1);
        }

        // Update I(L(R)) and I(N(R))
        if (customerCounter.get(customerKey) == 1){
            customerIL.put(customerKey, customer);
        } else{
            customerIN.put(customerKey, customer);
        }

        // Parent relation join child relation
        if (customerIL.contains(customerKey)){
            Nation nation = nationIL.get(customer.getC_nationKey());
            String flag = (nation.getFlag().equals("+") && customer.getFlag().equals("+")) ? "+" : "-";

            NationCustomerJointSchema nationCustomerJointValue= new NationCustomerJointSchema(
                    flag,
                    customer.getC_custKey(),
                    customer.getC_nationKey(),
                    nation.getN_nationKey(),
                    nation.getN_name()
            );
            collector.collect(nationCustomerJointValue);
        }
    }
}

