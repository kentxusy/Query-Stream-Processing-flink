package hkust.impl;

import hkust.schema.VolumeShippingResult;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;

public class AggregationImpl extends ProcessFunction<VolumeShippingResult, Tuple4<String, String, Integer, Double>> {
    private static final String INSERT = "+";
    private static final String DELETE = "-";

    MapState<Tuple, Double> output;
    MapState<Tuple, VolumeShippingResult> volumeShippingResult;

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        output = getRuntimeContext().getMapState(new MapStateDescriptor<>("output", Types.TUPLE(), Types.DOUBLE));
        volumeShippingResult = getRuntimeContext().getMapState(new MapStateDescriptor<>("volumeShippingResult", Types.TUPLE(), TypeInformation.of(VolumeShippingResult.class)));
    }

    @Override
    public void processElement(VolumeShippingResult value, Context context, Collector<Tuple4<String, String, Integer, Double>> collector) throws Exception {
        Tuple lineItemKey = Tuple2.of(value.getL_orderKey(), value.getL_lineNumber());
        Tuple outKey = Tuple3.of(value.getSupp_nation(), value.getCust_nation(), value.getL_year());
        String currentFlag = value.getFlag();
        Double volume = value.getVolume();

    //     if (volumeShippingResult.contains(lineItemKey)){
    //         String oldFlag = volumeShippingResult.get(lineItemKey).getFlag();
    //         volumeShippingResult.remove(lineItemKey);
    //         volumeShippingResult.put(lineItemKey, value);
    //         if (INSERT.equals(oldFlag) && DELETE.equals(currentFlag)){
    //             updateOutputAndCollect(outKey, -volume, collector);
    //         } else if (DELETE.equals(oldFlag) && INSERT.equals(currentFlag)) {
    //             updateOutputAndCollect(outKey, volume, collector);
    //         }
    //     } else {
    //         if (INSERT.equals(currentFlag)){
    //             volumeShippingResult.remove(lineItemKey);
    //             volumeShippingResult.put(lineItemKey, value);
    //             updateOutputAndCollect(outKey, volume, collector);
    //         }
    //     }
    // }
        if (DELETE.equals(currentFlag)){
            if (volumeShippingResult.contains(lineItemKey)) {
                updateOutputAndCollect(outKey, -volume, collector);
            }
            volumeShippingResult.remove(lineItemKey);
        } else if ( INSERT.equals(currentFlag)) {
            volumeShippingResult.put(lineItemKey, value);
            updateOutputAndCollect(outKey, volume, collector);
        }
    }
        

    private void updateOutputAndCollect(Tuple outKey, Double volume, Collector<Tuple4<String, String, Integer, Double>> collector) throws Exception {
        Double sumVolume = output.contains(outKey) ? output.get(outKey) : 0.0;
        output.put(outKey, sumVolume + volume);
        Tuple4<String, String, Integer, Double> t = Tuple4.of(outKey.getField(0), outKey.getField(1), outKey.getField(2), output.get(outKey));
        collector.collect(t);
    }
}

