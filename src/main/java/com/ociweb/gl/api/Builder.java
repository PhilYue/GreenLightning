package com.ociweb.gl.api;

import com.ociweb.pronghorn.network.config.HTTPHeaderKey;

/**
 * Base interface for an IoT device's hardware.
 * <p>
 * This interface is most commonly used in conjunction with a call
 * to {@link GreenApp#declareConfiguration(Builder)} in order for
 * a maker's code to declare any hardware connections and resources
 * that it makes use of.
 *
 * @author Nathan Tippy
 */
public interface Builder {


    /**
     * Initializes the hardware state machine with the given {@link Enum} state.
     *
     * @param state Initial state to use.
     *
     * @return A reference to this hardware instance.
     */
    <E extends Enum<E>> Builder startStateMachineWith(E state);

    /**
     * Sets the trigger rate of events on this hardware.
     *
     * @param rateInMS Rate in milliseconds to trigger events.
     *
     * @return A reference to this hardware instance.
     */
    Builder setTriggerRate(long rateInMS);

    /**
     * Sets the trigger rate of events on this hardware.
     *
     * @param trigger {@link TimeTrigger} to use for controlling trigger rate.
     *
     * @return A reference to this hardware instance.
     */
    Builder setTriggerRate(TimeTrigger trigger);

    
    void limitThreads(int threadLimit);
    
    void limitThreads();

	void parallelism(int parallel);

	long fieldId(int routeId, byte[] fieldName);
	
	int registerRoute(CharSequence route, HTTPHeaderKey ... headers);
      
	public void enableServer(boolean isTLS, boolean isLarge, String bindHost, int bindPort);
    
	public void enableTelemetry(boolean enable);
	
	public void setDefaultRate(long ns);
	
}
