/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kentender.nifi.opcua;

import static org.opcfoundation.ua.utils.EndpointUtil.selectByProtocol;
import static org.opcfoundation.ua.utils.EndpointUtil.selectBySecurityPolicy;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.*;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.opcfoundation.ua.application.Client;
import org.opcfoundation.ua.application.SessionChannel;
import org.opcfoundation.ua.builtintypes.DataValue;
import org.opcfoundation.ua.builtintypes.LocalizedText;
import org.opcfoundation.ua.builtintypes.NodeId;
import org.opcfoundation.ua.common.ServiceFaultException;
import org.opcfoundation.ua.common.ServiceResultException;
import org.opcfoundation.ua.core.Attributes;
import org.opcfoundation.ua.core.EndpointDescription;
import org.opcfoundation.ua.core.ReadRequest;
import org.opcfoundation.ua.core.ReadResponse;
import org.opcfoundation.ua.core.ReadValueId;
import org.opcfoundation.ua.core.TimestampsToReturn;
import org.opcfoundation.ua.transport.security.KeyPair;
import org.opcfoundation.ua.transport.security.SecurityPolicy;
import org.opcfoundation.ua.utils.EndpointUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Tags({"OPC", "OPCUA", "UA"})
@CapabilityDescription("Fetches a response from an OPC UA server based on configured name space and input item names")
@SeeAlso({})
@ReadsAttributes({@ReadsAttribute(attribute="", description="")})
@WritesAttributes({@WritesAttribute(attribute="", description="")})
@InputRequirement(Requirement.INPUT_REQUIRED)


public class FetchOPCUA extends AbstractProcessor {
	
	// TODO add scope for vars
	public static final Locale ENGLISH = Locale.ENGLISH;
	static KeyPair myClientApplicationInstanceCertificate = null;
	static KeyPair myHttpsCertificate = null;
	static String applicationName = null;
	static String url = "";
	
	// Create Client
	Client myClient = null;
	EndpointDescription[] endpoints = null;
	SessionChannel mySession = null;
	ReadResponse res = null;

	public static final PropertyDescriptor ENDPOINT = new PropertyDescriptor
            .Builder().name("Endpoint URL")
            .description("the opc.tcp address of the opc ua server")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    public static final PropertyDescriptor SECURITY_POLICY = new PropertyDescriptor
            .Builder().name("Security Policy")
            .description("How should Nifi authenticate with the UA server")
            .required(true)
            .allowableValues("None", "Basic128Rsa15", "Basic256", "Basic256Rsa256")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    // TODO change this to application and implement in the same manner as get endpoint
    public static final PropertyDescriptor APPLICATION_NAME = new PropertyDescriptor
    		.Builder().name("Application Name")
            .description("The application name is used to label certificates identifying this application")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    public static final PropertyDescriptor PROTOCOL = new PropertyDescriptor
            .Builder().name("Transfer Protocol")
            .description("How should Nifi communicate with the OPC server")
            .required(true)
            .allowableValues("opc.tcp")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    public static final Relationship SUCCESS = new Relationship.Builder()
            .name("Success")
            .description("Successful OPC read")
            .build();
    
    public static final Relationship FAILURE = new Relationship.Builder()
            .name("FAILURE")
            .description("Failed OPC read")
            .build();

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(ENDPOINT);
        descriptors.add(SECURITY_POLICY);
        descriptors.add(APPLICATION_NAME);
        descriptors.add(PROTOCOL);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<Relationship>();
        relationships.add(SUCCESS);
        relationships.add(FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
    	
    	final ComponentLog logger = getLogger();
    	
    	applicationName = context.getProperty(APPLICATION_NAME).getValue();
    	url = context.getProperty(ENDPOINT).getValue();
		
    	// Load Client's certificates from file or create new certs
		if (context.getProperty(SECURITY_POLICY).getValue() == "None"){
			// Build OPC Client
			myClientApplicationInstanceCertificate = null;
						
		} else {

			myHttpsCertificate = Utils.getHttpsCert(applicationName);
			
			// Load or create HTTP and Client's Application Instance Certificate and key
			switch (context.getProperty(SECURITY_POLICY).getValue()) {
				
				case "Basic128Rsa15":{
					myClientApplicationInstanceCertificate = Utils.getCert(applicationName, SecurityPolicy.BASIC128RSA15);
					break;
					
				}case "Basic256": {
					myClientApplicationInstanceCertificate = Utils.getCert(applicationName, SecurityPolicy.BASIC256);
					break;
					
				}case "Basic256Rsa256": {
					myClientApplicationInstanceCertificate = Utils.getCert(applicationName, SecurityPolicy.BASIC256SHA256);
					break;
				}
			}
		}
		
		// Create Client
		// TODO need to move this to service or on schedule method
		myClient = Client.createClientApplication( myClientApplicationInstanceCertificate ); 
		myClient.getApplication().getHttpsSettings().setKeyPair(myHttpsCertificate);
		myClient.getApplication().addLocale( ENGLISH );
		myClient.getApplication().setApplicationName( new LocalizedText(applicationName, Locale.ENGLISH) );
		myClient.getApplication().setProductUri( "urn:" + applicationName );
		
		// Retrieve and filter end point list
		// TODO need to move this to service or on schedule method
		
		try {
			endpoints = myClient.discoverEndpoints(url);
		} catch (ServiceResultException e1) {
			// TODO Auto-generated catch block
			
			logger.error(e1.getMessage());
		}
		
		switch (context.getProperty(SECURITY_POLICY).getValue()) {
			
			case "Basic128Rsa15":{
				endpoints = selectBySecurityPolicy(endpoints,SecurityPolicy.BASIC128RSA15);
				break;
			}
			case "Basic256": {
				endpoints = selectBySecurityPolicy(endpoints,SecurityPolicy.BASIC256);
				break;
			}	
			case "Basic256Rsa256": {
				endpoints = selectBySecurityPolicy(endpoints,SecurityPolicy.BASIC256SHA256);
				break;
			}
			default :{
				endpoints = selectBySecurityPolicy(endpoints,SecurityPolicy.NONE);
				logger.error("No security mode specified");
				break;
			}
		}
		
		// For now only opc.tcp has been implemented
		endpoints = selectByProtocol(endpoints, "opc.tcp");
		
		// Finally confirm the provided end point is in the list
 		endpoints = EndpointUtil.selectByUrl(endpoints, url);
 		
 		logger.debug(endpoints.length + " endpoints found");
	}

    /* (non-Javadoc)
     * @see org.apache.nifi.processor.AbstractProcessor#onTrigger(org.apache.nifi.processor.ProcessContext, org.apache.nifi.processor.ProcessSession)
     */
    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
    	
    	final ComponentLog logger = getLogger();
    	
    	// Initialize  response variable
        final AtomicReference<String> reqTagname = new AtomicReference<>();
        final AtomicReference<String> serverResponse = new AtomicReference<>();
        
        
        FlowFile flowFile = session.get();
        if ( flowFile == null ) {
            return;
        }
        
        // Read tag name from flow file content
        session.read(flowFile, new InputStreamCallback() {
            @Override
            public void process(InputStream in) throws IOException {
            	
                try{
                	String tagname = new BufferedReader(new InputStreamReader(in))
                	  .lines().collect(Collectors.joining("\n"));

                    reqTagname.set(tagname);
                    
                }catch (Exception e) {
        			// TODO Auto-generated catch block
        			e.printStackTrace();
        		}
        		
            }
            
        });
        
        // Build nodes to read string 
        // TODO move this to a expanded node id created from the input string

        ReadValueId[] NodesToRead = { 
				new ReadValueId(NodeId.parseNodeId(reqTagname.get()), Attributes.Value, null, null )
		};
        
        // Form OPC request
  		ReadRequest req = new ReadRequest();		
  		req.setMaxAge(500.00);
  		req.setTimestampsToReturn(TimestampsToReturn.Both);
  		req.setRequestHeader(null);
  		req.setNodesToRead(NodesToRead);

  		// Create and activate session
  		
  		/*
  		 * This needs to be maintained by a service 
  		 * with connection reference passed in the processor instance
  		 * 
  		 * */ 
  		
  		try {
  			// TODO pick a method for handling situations where more than one end point remains
  			mySession = myClient.createSessionChannel(endpoints[0]);
  			mySession.activate();
  			
  		} catch (ServiceResultException e1) {
  			// TODO Auto-generated catch block THIS NEEDS TO FAIL IN A SPECIAL WAY TO BE RE TRIED 
  			e1.printStackTrace();
  		}
  					
  		// Submit OPC Read and handle response
  		try{
          	res = mySession.Read(req);
              DataValue[] values = res.getResults();
              // TODO need to check the result for errors and other quality issues
              serverResponse.set(reqTagname.get() + "," + values[0].getValue().toString()  + ","+ values[0].getServerTimestamp().toString() );
              
          }catch (Exception e) {
  			// TODO Auto-generated catch block
  			e.printStackTrace();
  			session.transfer(flowFile, FAILURE);
  		}
  		
        // Write the results back out to flow file
        flowFile = session.write(flowFile, new OutputStreamCallback() {

            @Override
            public void process(OutputStream out) throws IOException {
            	out.write(serverResponse.get().getBytes());
            	
            }
            
        });
        
        session.transfer(flowFile, SUCCESS);
        
        // Close the session 
        
        /*
         * ( is this necessary or common practice.  
         * Timeouts clean up abandoned sessions ??? )*
         */
        
        try {
			mySession.close();
		} catch (ServiceFaultException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ServiceResultException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
    }
    
}
