package org.fitchfamily.android.dejavu;

/**
 * Created by tfitch on 10/30/17.
 */

import static org.fitchfamily.android.dejavu.BackendServiceKt.*;
import static org.fitchfamily.android.dejavu.RfEmitterKt.*;
import static org.fitchfamily.android.dejavu.RfCharacteristicsKt.rfchar;

import android.location.Location;
import android.os.Bundle;

class WeightedAverage {
    private static final String TAG="DejaVu wgtAvg";
    private static final float MINIMUM_BELIEVABLE_ACCURACY = 15.0F;

    private int count;
    private long timeMs;
    private long mElapsedRealtimeNanos;

    private class simpleWeightedAverage {
        // See: https://physics.stackexchange.com/a/329412 for details about estimating
        // error on weighted averages
        private double wSum;
        private double wSum2;
        private double mean;
        private double sdAccum;

        simpleWeightedAverage() {
            reset();
        }

        void reset() {
            wSum = wSum2 = mean = sdAccum = 0.0;
        }

        void add(double x, double sd, double weight) {
            wSum = wSum + weight;
            wSum2 = wSum2 + (weight * weight);

            double oldMean = mean;
            mean = oldMean + (weight / wSum) * (x - oldMean);

            sdAccum += (weight*weight)*(sd*sd);
        }

        double getMean() {
            return mean;
        }

        double getStdDev() {
            return Math.sqrt((1.0/wSum2)*sdAccum);
        }
    }

    private final simpleWeightedAverage latEst;
    private final simpleWeightedAverage lonEst;

    WeightedAverage() {
        latEst = new simpleWeightedAverage();
        lonEst = new simpleWeightedAverage();
        reset();
    }

    private void reset() {
        latEst.reset();
        lonEst.reset();

        count = 0;
        timeMs = 0;
        mElapsedRealtimeNanos = 0;
    }

    public void add(Location loc) {
        if (loc == null)
            return;

        //
        // We weight each location based on the signal strength, the higher the
        // strength the higher the weight. And we also use the estimated
        // coverage diameter. The larger the diameter, the lower the weight.
        //
        // ASU (signal strength) has been hard limited to always be >= 1
        // Accuracy (estimate of coverage radius) has been hard limited to always
        // be >= a emitter type minimum.
        //
        // So we are safe in computing the weight by dividing ASU by Accuracy.
        //

        float asu = loc.getExtras().getInt(LOC_ASU);
        double weight = asu / loc.getAccuracy();

        count++;
        //Log.d(TAG,"add() entry: weight="+weight+", count="+count);

        double asuAdjustedAccuracy;
        String typeString = loc.getExtras().getString(LOC_RF_TYPE);
        if (typeString != null) {
            EmitterType type = EmitterType.valueOf(typeString);
            // loc.accuracy is set to RfEmitter.radius, but at least EmitterType.minimumRange
            // asu goes from 1 (bad) to 31 (good) -> do a linear interpolation
            // with asu 1 we have loc.accuracy, with asu 31 we are close to minimumRange
            //  but we avoid actually going to minimumRange by adding 10 to maximum asu,
            //  as this reduces potential issues with "too accurate" values
            // todo: this could also be done in RfEmitter.generateLocation, but then
            //  a. asu is incorporated in weight twice, so this should probably be adjusted
            //      maybe just use 1/accuracy or sth
            //  b. the improved accuracy is also used in BackendService.divideInGroups
            //      could this lead to bad effects? it might lead to exclusion of some wifis from
            //      groups which leads to groups of wifis with worse accuracy resp. asu being preferred
            double minAcc = rfchar(type).getMinimumRange();
            asuAdjustedAccuracy = minAcc + (1 - ((asu - MINIMUM_ASU) * 1.0 / (MAXIMUM_ASU + 10))) * (loc.getAccuracy() - minAcc);
        } else asuAdjustedAccuracy = loc.getAccuracy();

        //
        // Our input has an accuracy based on the detection of the edge of the coverage area.
        // So assume that is a high (two sigma) probability and, worse, assume we can turn that
        // into normal distribution error statistic. We will assume our standard deviation (one
        // sigma) is half of our accuracy.
        //
        double stdDev = asuAdjustedAccuracy * METER_TO_DEG / 2.0;
        double cosLat = Math.max(MIN_COS, Math.cos(Math.toRadians(loc.getLatitude())));

        latEst.add(loc.getLatitude(), stdDev, weight);
        lonEst.add(loc.getLongitude(), stdDev * cosLat, weight);

        timeMs = Math.max(timeMs, loc.getTime());
        mElapsedRealtimeNanos = Math.max(mElapsedRealtimeNanos, loc.getElapsedRealtimeNanos());
    }

    public Location result() {
        if (count < 1)
            return null;

        final Location location = new Location(LOCATION_PROVIDER);

        location.setTime(timeMs);
        location.setElapsedRealtimeNanos(mElapsedRealtimeNanos);

        location.setLatitude(latEst.getMean());
        location.setLongitude(lonEst.getMean());

        //
        // Accuracy estimate is in degrees, convert to meters for output.
        // We calculate North-South and East-West independently, convert to a
        // circular radius by finding the length of the diagonal.
        //
        double sdMetersLat = latEst.getStdDev() * DEG_TO_METER;
        double cosLat = Math.max(MIN_COS, Math.cos(Math.toRadians(latEst.getMean())));
        double sdMetersLon = lonEst.getStdDev() * DEG_TO_METER * cosLat;

        float acc = (float) Math.max(Math.sqrt(sdMetersLat * sdMetersLat + sdMetersLon * sdMetersLon), MINIMUM_BELIEVABLE_ACCURACY);
        location.setAccuracy(acc);

        Bundle extras = new Bundle();
        extras.putLong("AVERAGED_OF", count);
        location.setExtras(extras);

        return location;
    }
}
