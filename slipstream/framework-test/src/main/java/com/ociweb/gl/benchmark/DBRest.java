package com.ociweb.gl.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.ociweb.gl.api.GreenRuntime;
import com.ociweb.gl.api.HTTPRequestReader;
import com.ociweb.gl.api.HTTPResponseService;
import com.ociweb.gl.api.PubSubMethodListener;
import com.ociweb.gl.api.RestMethodListener;
import com.ociweb.gl.api.TickListener;
import com.ociweb.json.encode.JSONRenderer;
import com.ociweb.pronghorn.network.config.HTTPContentTypeDefaults;
import com.ociweb.pronghorn.pipe.ObjectPipe;

import io.reactiverse.pgclient.PgIterator;
import io.reactiverse.pgclient.PgPool;
import io.reactiverse.pgclient.Tuple;

public class DBRest implements RestMethodListener, PubSubMethodListener, TickListener {

	private final PgPool pool;
	private final ThreadLocalRandom localRandom = ThreadLocalRandom.current();

	private final ObjectPipe<ResultObject> inFlight;
	

	
	public DBRest(GreenRuntime runtime, PgPool pool, ObjectPipe<ResultObject> inFlight, int maxResponseSize) {
		this.pool = pool;		
		this.inFlight = inFlight; 
		this.service = runtime.newCommandChannel().newHTTPResponseService(1<<8, maxResponseSize); 
					
	}		
	
	private int randomValue() {
		return 1+localRandom.nextInt(10000);
	}		
	
	public boolean multiRestRequest(HTTPRequestReader request) { 

		int queries = 1;
		if (Struct.DB_MULTI_ROUTE_INT == request.getRouteAssoc() ) {		
			queries = Math.min(Math.max(1, (request.structured().readInt(Field.QUERIES))),500);		
		}		
		
		if (inFlight.hasRoomFor(queries)) {
			
			int q = queries;
			while (--q >= 0) {
			
					final ResultObject target = inFlight.headObject();
					assert(null!=target);
				
					target.setConnectionId(request.getConnectionId());
					target.setSequenceId(request.getSequenceCode());
					target.setStatus(-2);//out for work	
					target.setGroupSize(queries);
				
					pool.preparedQuery("SELECT * FROM world WHERE id=$1", Tuple.of(randomValue()), r -> {
							if (r.succeeded()) {
								
								PgIterator resultSet = r.result().iterator();
						        Tuple row = resultSet.next();			        
						        
						        target.setId(row.getInteger(0));
						        target.setResult(row.getInteger(1));					
								target.setStatus(200);
								
							} else {
								System.out.println("fail: "+r.cause().getLocalizedMessage());
								target.setStatus(500);
							}				
						});	
								
					inFlight.moveHeadForward(); //always move to ensure this can be read.
			
			}
				
			return true;
		} else {
			return false;
		}	
	}

	

	
	public boolean singleRestRequest(HTTPRequestReader request) { 

		final ResultObject target = inFlight.headObject();
		if (null!=target) {
			target.setConnectionId(request.getConnectionId());
			target.setSequenceId(request.getSequenceCode());
			target.setStatus(-2);//out for work	
			target.setGroupSize(0);//do not put in a list so mark as 0.
		
			pool.preparedQuery("SELECT * FROM world WHERE id=$1", Tuple.of(randomValue()), r -> {
					if (r.succeeded()) {
						
						PgIterator resultSet = r.result().iterator();
				        Tuple row = resultSet.next();			        
				        
				        target.setId(row.getInteger(0));
				        target.setResult(row.getInteger(1));					
						target.setStatus(200);
						
					} else {
						System.out.println("fail: "+r.cause().getLocalizedMessage());
						target.setStatus(500);
					}				
				});

			
			inFlight.moveHeadForward(); //always move to ensure this can be read.
			return true;
		} else {
			return false;//can not pick up new work now			
		}
	}


	
	
	
	
	
	
	public boolean updateRestRequest(HTTPRequestReader request) {
		
		
//		pool.preparedQuery("SELECT * FROM world WHERE id=$1", Tuple.of(randomValue()), r -> {
//			if (r.succeeded()) {
//				
//				PgIterator resultSet = r.result().iterator();
//		        Tuple row = resultSet.next();			        
//		        
//		        target.setId(row.getInteger(0));
//		        target.setResult(row.getInteger(1));					
//				target.setStatus(200);
//				
//			} else {
//				System.out.println("fail: "+r.cause().getLocalizedMessage());
//				target.setStatus(500);
//			}				
//		});

		
//		List<Tuple> batch = new ArrayList<>(queries);
//        for (World world : worlds) {
//            batch.add(Tuple.of(world.randomNumber, world.id));
//        }
//
//        pool.preparedBatch("UPDATE world SET randomnumber=$1 WHERE id=$2", batch, ar -> {

        	
		return false;//service.publishHTTPResponse(request, 404);
		
	}
	
	////////////////////////////////////
	////////////////////////////////////
	
	private final JSONRenderer<List<ResultObject>> multiTemplate = new JSONRenderer<List<ResultObject>>()
	    	  .array((o,i,node) -> i<o.size()?o:null)
		          .startObject((o, i) -> o.get(i))
					.integer("id", o -> o.getId() )
					.integer("randomNumber", o -> o.getResult())
		          .endObject();
	
	private final JSONRenderer<ResultObject> singleTemplate = new JSONRenderer<ResultObject>()
		   	  .startObject()
				.integer("id", o -> o.getId() )
				.integer("randomNumber", o -> o.getResult())
	          .endObject();
	
	private boolean collectionPending = false;

	//this collector is for the multi db test so we can collect all the objects until we have them all for 
	//the request we are currently sending back
	private final List<ResultObject> collector = new ArrayList<ResultObject>();
	private final HTTPResponseService service;


	@Override
	public void tickEvent() { 
		
		ResultObject temp = inFlight.tailObject();
		while (isReady(temp)) {			
			if (consumeResultObject(temp)) {
				inFlight.moveTailForward();
				temp = inFlight.tailObject();
			} else {
				break;
			}
		}	   
		
	}

	private boolean isReady(ResultObject temp) {

		if (collectionPending) {
			//now ready to send, we have all the data	
			if (publishMultiResponse(collector.get(0).getConnectionId(), collector.get(0).getSequenceId() )) {
				inFlight.moveTailForward();//only move forward when it is consumed.
				collectionPending = false;
			} else {
				return false;
			}
		}
		
		return null!=temp && temp.getStatus()>=0;
	}

	private boolean consumeResultObject(final ResultObject t) {
		boolean ok;
						
		///////////////////////////////
		if (0 == t.getGroupSize()) {	
			ok = service.publishHTTPResponse(t.getConnectionId(), t.getSequenceId(), 200,
				   HTTPContentTypeDefaults.JSON,
				   w-> {
					   singleTemplate.render(w, t);
					   t.setStatus(-1);
				   });					
		} else {
			//collect all the objects
			collector.add(t);					
			if (collector.size() == t.getGroupSize()) {
				//now ready to send, we have all the data						
				ok =publishMultiResponse(t.getConnectionId(), t.getSequenceId());
				
			} else {
				ok = true;//added to list
			}				
		}
		return ok;
	}

	private boolean publishMultiResponse(long conId, long seqCode) {
		boolean result =  service.publishHTTPResponse(conId, seqCode, 200,
					    				   HTTPContentTypeDefaults.JSON,
					    				   w-> {
					    					   multiTemplate.render(w, collector);
					    					   int c = collector.size();
					    					   while (--c>=0) {
					    						   assert(collector.get(c).getConnectionId() == conId);
					    						   assert(collector.get(c).getSequenceId() == seqCode);					    						   
					    						   collector.get(c).setStatus(-1);
					    					   }
					    					   collector.clear();
					    				   });
		collectionPending = !result;
		return result;
	}
	
	
}

