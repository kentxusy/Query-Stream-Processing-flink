package hkust;

import hkust.impl.AggregationImpl;
import hkust.impl.NationCustomerJointImpl;
import hkust.impl.NationCustomerOrderJointImpl;
import hkust.impl.NationSupplierJointImpl;
import hkust.impl.NationSupplierLineItemJointImpl;
import hkust.impl.VolumeShippingImpl;
import hkust.schema.Customer;
import hkust.schema.LineItem;
import hkust.schema.Nation;
import hkust.schema.NationCustomerJointSchema;
import hkust.schema.NationCustomerOrderJointSchema;
import hkust.schema.NationSupplierJointSchema;
import hkust.schema.NationSupplierLineItemJointSchema;
import hkust.schema.Order;
import hkust.schema.Supplier;
import hkust.schema.VolumeShippingResult;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
*
* This is the main process for the implementation.
*
*/
public class Processing {
    // Num of parallel workers
    private static final int PARALLEL_WORKER_NUM = 1;
    // Data Scale, used to get input path
    private static String scale = "1";
    // DataTime format
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static void main(String[] args) throws Exception {
        // Set up the execution environment
//        Configuration configuration = new Configuration();
//        configuration.setString("rest.port", "9091");
//        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(configuration);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // Set up parallelism
        env.setParallelism(PARALLEL_WORKER_NUM);

        // read data from tbl file
        String[] pathList = getInputPath(scale);
        DataStreamSource<String> customerText = env.readTextFile(pathList[0]);
        DataStreamSource<String> nationText = env.readTextFile(pathList[1]);
        DataStreamSource<String> orderText = env.readTextFile(pathList[2]);
        DataStreamSource<String> supplierText = env.readTextFile(pathList[3]);
        DataStreamSource<String> lineItemText = env.readTextFile(pathList[4]);

        // Data preprocessing
        SingleOutputStreamOperator<Customer> customer = customerText.map(s -> {
            String[] items = s.split("\\|");
            return new Customer(items[0], Integer.valueOf(items[1]), items[2], items[3], Integer.valueOf(items[4]), items[5], Float.parseFloat(items[6]), items[7], items[8]);
        });
        SingleOutputStreamOperator<Nation> nation = nationText.map(s -> {
            String[] items = s.split("\\|");
            return new Nation(items[0], Integer.valueOf(items[1]), items[2], Integer.valueOf(items[3]), items[4]);
        }).filter(nation1 -> nation1.getN_name().equals("ALGERIA") || nation1.getN_name().equals("BRAZIL"));

        // Data to java objects
        SingleOutputStreamOperator<Order> order = orderText.map(s -> {
            String[] items = s.split("\\|");
            return new Order(items[0], Integer.valueOf(items[1]), Integer.valueOf(items[2]), items[3], Double.valueOf(items[4]), LocalDate.parse(items[5], formatter), items[6], items[7], Integer.valueOf(items[8]), items[9]);
        });
        SingleOutputStreamOperator<Supplier> supplier = supplierText.map(s -> {
            String[] items = s.split("\\|");
            return new Supplier(items[0], Integer.valueOf(items[1]), items[2], items[3], Integer.valueOf(items[4]), items[5], Double.valueOf(items[6]), items[7]);
        });
        SingleOutputStreamOperator<LineItem> lineItem = lineItemText.map(s -> {
            String[] items = s.split("\\|");
            return new LineItem(items[0], Integer.valueOf(items[1]), Integer.valueOf(items[2]), Integer.valueOf(items[3]), Integer.valueOf(items[4]), Double.valueOf(items[5]), Double.valueOf(items[6]), Double.valueOf(items[7]), Double.valueOf(items[8]),
                    items[9], items[10], LocalDate.parse(items[11], formatter), LocalDate.parse(items[12], formatter), LocalDate.parse(items[13], formatter), items[14], items[15], items[16]);
        }).filter(lineItem1 -> lineItem1.getL_shipDate().compareTo(LocalDate.of(1995,1,1)) >=0 &&
                lineItem1.getL_shipDate().compareTo(LocalDate.of(1996,12,31)) <= 0);

        // Record start time
        long startTime = System.currentTimeMillis();

        // result1 = nation.connect(customer).connect(order)
        SingleOutputStreamOperator<NationCustomerOrderJointSchema> nationCustomerOrderJointStream = nation.connect(customer)
                .keyBy(Nation::getN_nationKey, Customer::getC_nationKey)
                .process(new NationCustomerJointImpl())
                .connect(order).keyBy(NationCustomerJointSchema::getC_custKey, Order::getO_custKey)
                .process(new NationCustomerOrderJointImpl());

        // result2 = nation.connect(Supplier).connect(lineItem)
        SingleOutputStreamOperator<NationSupplierLineItemJointSchema> nationSupplierLineItemJointStream = nation.connect(supplier)
                .keyBy(Nation::getN_nationKey, Supplier::getS_nationKey)
                .process(new NationSupplierJointImpl())
                .connect(lineItem)
                .keyBy(NationSupplierJointSchema::getS_suppKey, LineItem::getL_suppKey)
                .process(new NationSupplierLineItemJointImpl());

        // Volume shipping result = result1.connect(result2)
        nationCustomerOrderJointStream.connect(nationSupplierLineItemJointStream)
                .keyBy(NationCustomerOrderJointSchema::getO_orderKey, NationSupplierLineItemJointSchema::getL_orderKey)
                .process(new VolumeShippingImpl())
                .keyBy(new KeySelector<VolumeShippingResult, Tuple3<String, String, Integer>>() {
                    @Override
                    public Tuple3<String, String, Integer> getKey(VolumeShippingResult results) throws Exception {
                        return Tuple3.of(results.getSupp_nation(), results.getCust_nation(), results.getL_year());
                    }})
                .process(new AggregationImpl())
//                .filter(value -> value.f0.equals("ALGERIA") && value.f1.equals("BRAZIL") ||
//                        value.f0.equals("BRAZIL")&&value.f1.equals("ALGERIA"))
                .print();

        env.execute();

        // End time
        long endTime = System.currentTimeMillis();
        System.out.println("Running Time：" + (endTime - startTime) + "ms");
    }

    private static String[] getInputPath(String scale) {
        String[] pathList = new String[5];
        pathList[0] = "input/scale" + scale +"/customer_updated.tbl";
        pathList[1] = "input/scale" + scale +"/nation_updated.tbl";
        pathList[2] = "input/scale" + scale +"/orders_updated.tbl";
        pathList[3] = "input/scale" + scale +"/supplier_updated.tbl";
        pathList[4] = "input/scale" + scale +"/lineitem_updated.tbl";
        return pathList;

    }
}
