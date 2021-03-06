package com.dgis.JOuST.serial;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.dgis.JOuST.OBDInterface;
import com.dgis.JOuST.PIDNotFoundException;
import com.dgis.JOuST.PIDResultListener;
import com.dgis.util.Logger;

/*
 * Copyright (C) 2009 Giacomo Ferrari
 * This file is part of JOuST.
 *  JOuST is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  JOuST is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with JOuST.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * An implementation of ObdSerial that can talk to the ELM32X line of interfaces.
 *
 * Copyright (C) 2009 Giacomo Ferrari
 * @author Giacomo Ferrari
 */

public class ElmSerial implements ObdSerial {
	
	public static final int  ATZ_TIMEOUT=           1500;
	public static final int  AT_TIMEOUT=            500;

	private static final byte SPECIAL_DELIMITER = '\r';

	private static Logger logger = Logger.getInstance();

	private InputStream input;
	private OutputStream output;
	
	boolean isOpen=false;
	
	// ///PROTOCOL SPECIFIC VARIABLES/////
	private ELMInterfaceType device=ELMInterfaceType.UNKNOWN_INTERFACE;

	/**
	 * Construct a new ElmSerial with the specified Streams to use
	 * for communication. Will not try to perform any communication
	 * yer.
	 * @param in
	 * @param out
	 */
	public ElmSerial(InputStream in, OutputStream out) {
		try {
			reopen(in,out);
		} catch (IOException e) {
			logger.logError("Very unexpected error. Contact author, as he's an idiot.");
			e.printStackTrace();
		}
	}

	/**
	 * Re-uses this instance with a different set of streams.
	 * If currently open, will call stop() first.
	 * Remember to call resetAndHandshake() before using.
	 * @param in
	 * @param out
	 * @throws IOException 
	 */
	public void reopen(InputStream in, OutputStream out) throws IOException {
		if(isOpen) stop();
		input=in;
		output=out;
		isOpen=true;
	}

	@Override
	public void stop() throws IOException {
		logger.logInfo("Closing port.");
		input.close();
		output.close();
		input=null;
		output=null;
	}

	@Override
	public boolean isOpen() {
		return isOpen;
	}

	/**
	 * Sends a String command to the device through the Stream.
	 * @param c
	 * @throws IOException
	 */
	private void send_command(String c) throws IOException {
		//TODO: Why aren't I using c.getBytes()?
		byte[] buf = new byte[c.length()];
		for (int x = 0; x < buf.length; x++)
			buf[x] = (byte) c.charAt(x);
		send_command(buf);
	}
	
	/**
	 * Sends a command to the device through the Stream.
	 * @param command
	 * @throws IOException
	 */
	public void send_command(byte[] command) throws IOException {
		output.write(command);
		output.write(new byte[] { '\r' });
		output.flush();
	}

	/**
	 * Will attempt to determine the type of response that was received from the device.
	 * @param cmd_sent
	 * @param msg_received
	 * @return the return value of the visitor call.
	 * @throws IOException
	 */
	public Object process_response(ElmResponseVisitor visit, byte[] cmd_sent, byte[] msg_received)
			throws IOException {
		int i = 0;
		int msgPos = 0; //Start of the message. May not be 0 if echo is on.
		if (cmd_sent != null) {
			//See if msg_received starts with cmd_sent.
			//If so, we know echo is on, and we disable it.
			//Also keep track of where the start of the reply
			//should be (msgPos).
			boolean echoOn = true;
			for (i = 0; i<cmd_sent.length && cmd_sent[i] != 0; i++) {
				if (cmd_sent[i] != msg_received[msgPos]) // if the characters are not the same
				{
					echoOn = false;
					break;
				}
				msgPos++;
			}

			if (echoOn)
				turnOffEcho();
			else
				msgPos = 0;
		}

		// Strip off nulls and special characters at start of reply.
		while (msg_received[msgPos] > 0 && (msg_received[msgPos] <= ' '))
			msgPos++;

		// Pull out reply string.
		StringBuffer msgBuf = new StringBuffer(msg_received.length-msgPos);
		//Accept characters until null hit.
		for(int j=msgPos; j<msg_received.length; j++){
			if(msg_received[j]==0) break;
			msgBuf.append((char)msg_received[j]);
		}
		String msg = msgBuf.toString();
		
		//Collapse whitespace & prompt
		msg = msg.replaceAll("\\s|>", "");
		
		//Get rid of useless bits...
		if (msg.startsWith("SEARCHING..."))
			msg=msg.substring(12);
		else if (msg.startsWith("BUSINIT:OK"))
			msg=msg.substring(12);
		else if (msg.startsWith("BUSINIT:...OK"))
			msg=msg.substring(15);
		
		//Check for <DATA ERROR>
		int indexOfLT = msg.indexOf('<');
		if(indexOfLT>=0){
			if(msg.startsWith("<DATAERROR", indexOfLT)) //Remember, spaces are gone, as is >
				return visit.dataError2();
			else
				return visit.rubbish();
		}
		
		//Check for hex number.
		boolean isHex = true;
		//Check every character for non-hexness
		for(char c : msg.toCharArray()){
			if(!(Character.isDigit(c) || (c>='a' && c<='f') || (c>='A' && c <= 'F'))){
				isHex=false;
				break;
			}
		}
		if(isHex) {return visit.hexData();}
		
		if (msg.contains("NODATA"))
			{return visit.noData();}
		if (msg.contains("UNABLETOCONNECT"))
			{return visit.unableToConnect();}
		if (msg.contains("BUSBUSY"))
			{return visit.busBusy();}
		if (msg.contains("DATAERROR"))
			{return visit.dataError();}
		if (msg.contains("BUSERROR") || msg.contains("FBERROR"))
			{return visit.busError();}
		if (msg.contains("CANERROR"))
			{return visit.CANError();}
		if (msg.contains("BUFFERFULL"))
			{return visit.bufferIsFull();}
		if (msg.contains("BUSINIT:ERROR") || msg.contains("BUSINIT:...ERROR"))
			{return visit.busInitError();}
		if (msg.contains("BUSINIT:") || msg.contains("BUSINIT:..."))
			{return visit.serialError();}
		if (msg.contains("?"))
			{return visit.unknownCommand();}
		if (msg.contains("ELM320"))
			{return visit.interfaceFound(ELMInterfaceType.INTERFACE_ELM320);}
		if (msg.contains("ELM322"))
			{return visit.interfaceFound(ELMInterfaceType.INTERFACE_ELM322);}
		if (msg.contains("ELM323"))
			{return visit.interfaceFound(ELMInterfaceType.INTERFACE_ELM323);}
		if (msg.contains("ELM327"))
			{return visit.interfaceFound(ELMInterfaceType.INTERFACE_ELM327);}

		logger.logWarning("Warning: Discarded apparent noise: |"+msg+"|");
		return visit.rubbish();
	}

	/**
	 * Attempts to turn off echo at the device to save bandwidth.
	 * @throws IOException in the event of a timeout or other error communicating.
	 */
	private void turnOffEcho() throws IOException {
		byte[] temp_buf = new byte[80];
		send_command("ate0"); // turn off the echo
		// wait for chip response or timeout
		// TODO test timeout
		boolean timedOut = false;
		while (true) {
			ELMReadResult res = read_comport(temp_buf, AT_TIMEOUT);
			if (res == ELMReadResult.PROMPT)
				break;
			else if (res == ELMReadResult.TIMEOUT) {
				timedOut = true;
				break;
			}
		}
		if (!timedOut) {
			send_command("atl0"); // turn off linefeeds
			while (true) {
				ELMReadResult res = read_comport(temp_buf, AT_TIMEOUT);
				if (res == ELMReadResult.PROMPT)
					break;
				else if (res == ELMReadResult.TIMEOUT) {
					timedOut = true;
					break;
				}
			}
		} else {
			throw new IOException("Timed out while trying to disable echo.");
		}
	}

	/**
	 * Attempts to read from the Stream.
	 * @param buf the buffer to read into.
	 * @param timeout Will return ElmReadResult.TIMEOUT after this much time without data.
	 * @return the result of the read.
	 * @throws IOException
	 */
	private ELMReadResult read_comport(byte[] buf, int timeout) throws IOException {
		//Wait for data.
		long startTime = System.currentTimeMillis();
		while(input.available()==0){
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if(System.currentTimeMillis()-startTime > timeout){
				//TODO log something
				return ELMReadResult.TIMEOUT;
			}
		}
		//Read the data.
		int len = input.read(buf);
		if (len == 0)
			return ELMReadResult.EMPTY;
		logger.logSuperfine("RX: " + new String(buf));
		for (int p = 0; p < len; p++) {
			if (buf[p] == '>') {
				return ELMReadResult.PROMPT;
			}
		}
		return ELMReadResult.DATA;
	}

	/**
	 * Converts an interface type to a human-readable String. Lifted from Scantool.
	 * @param interface_type
	 * @param protocol_id
	 * @return
	 */
	public static String get_protocol_string(ELMInterfaceType interface_type, int protocol_id) {
		switch (interface_type) {
		case INTERFACE_ELM320:
			return "SAE J1850 PWM (41.6 kBit/s)";
		case INTERFACE_ELM322:
			return "SAE J1850 VPW (10.4 kBit/s)";
		case INTERFACE_ELM323:
			return "ISO 9141-2 / ISO 14230-4 (KWP2000)";
		case INTERFACE_ELM327:
			switch (protocol_id) {
			case 0:
				return "N/A";
			case 1:
				return "SAE J1850 PWM (41.6 kBit/s)";
			case 2:
				return "SAE J1850 VPW (10.4 kBit/s)";
			case 3:
				return "ISO 9141-2";
			case 4:
				return "ISO 14230-4 KWP2000 (5-baud init)";
			case 5:
				return "ISO 14230-4 KWP2000 (fast init)";
			case 6:
				return "ISO 15765-4 CAN (11-bit ID, 500 kBit/s)";
			case 7:
				return "ISO 15765-4 CAN (29-bit ID, 500 kBit/s)";
			case 8:
				return "ISO 15765-4 CAN (11-bit ID, 250 kBit/s)";
			case 9:
				return "ISO 15765-4 CAN (29-bit ID, 250 kBit/s)";
			}
		}

		return "unknown";
	}

	// TODO Find what 'stop' is
	boolean find_valid_response(byte[] buf, byte[] response, String filter,
			int[] stop) {
		int in_ptr = 0; // in response
		int out_ptr = 0; // in buf
		buf[0] = 0;

		String responseString = new String(response);

		while (response[in_ptr] != 0) {
			// TODO check this logic
			if (responseString.startsWith(filter)) {
				while (response[in_ptr] > 0
						&& response[in_ptr] != SPECIAL_DELIMITER) // copy
																	// valid
																	// response
																	// into buf
				{
					out_ptr = in_ptr;
					in_ptr++;
					out_ptr++;
				}
				out_ptr = 0; // terminate string
				if (response[in_ptr] == SPECIAL_DELIMITER)
					in_ptr++;
				break;
			} else {
				// skip to the next delimiter
				while (response[in_ptr] > 0
						&& response[in_ptr] != SPECIAL_DELIMITER)
					in_ptr++;
				if (response[in_ptr] == SPECIAL_DELIMITER) // skip the
															// delimiter
					in_ptr++;
			}
		}

		if (stop != null)
			stop[0] = in_ptr;

		if (buf[0] != 0)
			return true;
		else
			return false;
	}

	// TODO make this not ugly

	//Lifted from Scantool.
	public ResetResult resetAndHandshake() throws IOException {
		StringBuffer response = new StringBuffer(256);
		logger.logInfo("Resetting hardware interface.");
		if(!isOpen){
			logger.logWarning("resetAndHandshake() called after stop().");
			throw new IOException("resetAndHandshake() called after stop().");
		}
		// case RESET_START:
		// wait until we either get a prompt or the timer times out
		long time = System.currentTimeMillis();
		while (true) {
			if (input.available() > 0) {
				if (input.read() == '>')
					break;
			} else {
				if (System.currentTimeMillis() - time > ATZ_TIMEOUT)
					break;
			}
		}
		logger.logVerbose("Sending ATZ.");
		send_command("atz"); // reset the chip

		// case RESET_WAIT_RX:
		byte[] buf = new byte[128];
		try {
			Thread.sleep(ATZ_TIMEOUT);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		ELMReadResult status = read_comport(buf, ATZ_TIMEOUT); // read comport
		while (status == ELMReadResult.DATA){ // if new data detected in com port buffer
			response.append(new String(buf)); // append contents of buf to
												// response
			status = read_comport(buf, ATZ_TIMEOUT);
		}
		if (status == ELMReadResult.PROMPT) // if '>' detected
		{
			logger.logVerbose("Got prompt.");
			response.append(new String(buf));
			process_response(new AElmResponseVisitor(){
				@Override
				public Object interfaceFound(ELMInterfaceType type) {
					device=type;
					return null;
				}
				@Override
				Object defaultCase() {
					String s = "Unexpected response while trying to find device identifier!";
					logger.logError(s);
					return null;
				}
			}, "atz".getBytes(), response.toString()
					.getBytes());
			logger.logVerbose("Response: "+device);
			switch(device){
			case INTERFACE_ELM323: case INTERFACE_ELM327:
				logger.logInfo("Found an "+device.toString());
				logger.logInfo("Waiting for ECU timeout...");
				return RESET_ECU_TIMEOUT(response);
			case INTERFACE_ELM320: case INTERFACE_ELM322:
				logger.logInfo("Found a "+device.toString());
				return new ResetResult(device.toString(), true);
			default:
				logger.logWarning("Unexpected response while trying to identify device: "+response.toString());
				return new ResetResult("Unexpected response while trying to identify device: "+response.toString(), false);	
			}
		} else if (status == ELMReadResult.TIMEOUT) // if the timer timed out
		{
			logger.logWarning("Interface was not found - time out.");
			return new ResetResult(device.toString(), false);
		}
		else{
			logger.logWarning("Unexpected response: "+device.toString());
			return new ResetResult(device.toString(), false);
		}
	}

	private ResetResult RESET_ECU_TIMEOUT(StringBuffer response) throws IOException {
		// if (serial_time_out) // if the timer timed out
		// {
		if (device == ELMInterfaceType.INTERFACE_ELM327) {
			logger.logVerbose("Sending 0100...");
			send_command("0100");
			response = new StringBuffer(256);
			logger.logInfo("Detecting OBD protocol...");
			return RESET_WAIT_0100(response);
		} else //TODO Is this right?
			return new ResetResult(device.toString(), true);
		// }
	}

	private ResetResult RESET_WAIT_0100(StringBuffer response) throws IOException {
		byte[] buf = new byte[128];
		while(true){
			ELMReadResult readStatus = read_comport(buf, ECU_TIMEOUT);
			//logger.logVerbose("Response: "+readStatus.toString());
			if (readStatus == ELMReadResult.DATA){ // if new data detected in com port buffer
				String dta = new String(buf);
				response.append(dta);
				continue;
			}
													// response
			else if (readStatus == ELMReadResult.PROMPT) // if we got the prompt
			{
				response.append(new String(buf));
				//TODO: semi-hack
				ResetResult res = (ResetResult) process_response(new AElmResponseVisitor(){
					@Override
					Object defaultCase() {
						return new ResetResult(device.toString(), false);
					}
					@Override
					public Object hexData() {
						return new ResetResult("OK.", true);
					}
					@Override
					public Object noData() {
						return new ResetResult("Did not receive a response.", false);
					}
					@Override
					public Object unableToConnect() {
						return new ResetResult("Unable to connect to interface.", false);
					}
				}, "0100".getBytes(), response.toString()
						.getBytes());
				return res;
	
			} else if (readStatus == ELMReadResult.TIMEOUT) // if the timer timed out
			{
				logger.logWarning("Interface not found");
				return new ResetResult("Did not receive a response.", false);
			}
			return new ResetResult("Interface not found.", false);
		}
	}

	@Override
	public void requestPID(final PIDResultListener list, final int pid, final int numBytes) throws IOException {
		if (isOpen) {
			String cmd = String.format("01%02X", pid);
			send_command(cmd); // send command for that particular sensor
			final byte[] buf = new byte[256];
			final StringBuffer response = new StringBuffer(255);
			ELMReadResult response_status = ELMReadResult.DATA;
			long start_time = System.currentTimeMillis();
			while (true) {
				response_status = read_comport(buf, OBD_REQUEST_TIMEOUT); // read comport
				String r = bytesToString(buf);
				response.append(r);
				if (response_status == ELMReadResult.DATA) // if data detected in com port buffer
				{
					continue;
				} else if (response_status == ELMReadResult.PROMPT) // if '>' detected
				{
					process_response(new AElmResponseVisitor(){
							@Override
							Object defaultCase(){
								list.error("Did not get a hexadecimal value back from interface when requesting PID#"+String.format("%02X",pid), pid);
								return null;
							}
							@Override
							public Object hexData() {
								String cmd = String.format("41%02X", pid);
								if (find_valid_response(buf, response.toString(), cmd,
										null)) {
									buf[4 + numBytes* 2] = 0;  // solves problem where response is padded with zeroes (i.e., '41 05 7C 00 00 00')
									//TODO calculate value here as per
									//sensor->formula((int)strtol(buf + 4, NULL, 16), buf); //plug the value into formula
									list.dataReceived(pid, numBytes, buf);
								} else {
									//TODO log something- got nothing back.
									list.error("Got no data back from interface when requesting PID#"+String.format("%02X",pid), pid);
								}
								return null;
							}
						}, cmd.getBytes(), response.toString().getBytes());
						return;
				} else if(response_status == ELMReadResult.EMPTY){
					if(System.currentTimeMillis() - start_time > OBD_REQUEST_TIMEOUT){
						//TODO log timeout
						list.error("Got no data back from interface when requesting PID#"+String.format("%02X",pid), pid);
						return;
					} else {
						//Not enough data, still not timed out
						continue;
					}
				} else {
					//TODO log something.
					list.error("Unknown error: "+ response_status.name(), pid);
				}
			}
		} else {
			logger.logWarning("requestPID() called after stop().");
			throw new IOException("requestPID() called after stop().");
		}
	}
	
	@Override
	public void requestPID(PIDResultListener list, int pid) throws IOException,
			PIDNotFoundException {
		Integer size = OBDInterface.PID_SIZES.get(pid);
		if(size == null) throw new PIDNotFoundException(pid);
		requestPID(list, pid, size);
	}

	@Override
	public void requestPID(PIDResultListener list, String name)
			throws IOException, PIDNotFoundException {
		Integer pid = OBDInterface.PID_NAMES.get(name);
		if(pid == null) throw new PIDNotFoundException(-1);
		Integer size = OBDInterface.PID_SIZES.get(pid);
		if(size == null) throw new PIDNotFoundException(pid);
		requestPID(list, pid, size);
		
	}

	// Lifted from ScanTool
	// TODO Convert this to Java style
	public static boolean find_valid_response(byte[] buf, String response,
			String filter, int[] endOfResp) {
		int in_ptr = 0; // in response
		int out_ptr = 0; // in buf

		buf[0] = 0;

		response = response.replaceAll(" ", "");
		
		while (in_ptr < response.length()) {
			if (response.startsWith(filter, in_ptr)) {
				while (in_ptr < response.length()
						&& response.charAt(in_ptr) != SPECIAL_DELIMITER)
				{
					//char x = response.charAt(in_ptr);
					buf[out_ptr] = (byte)response.charAt(in_ptr);
					in_ptr++;
					out_ptr++;
				}
				buf[out_ptr] = 0; // terminate string
				if (response.charAt(in_ptr) == SPECIAL_DELIMITER)
					in_ptr++;
				break;
			} else {
				// skip to the next delimiter
				while (in_ptr < response.length()
						&& response.charAt(in_ptr) != SPECIAL_DELIMITER)
					in_ptr++;
				//TODO if (response.charAt(in_ptr) != SPECIAL_DELIMITER) // skip the
																	// delimiter
					in_ptr++;
			}
		}

		if (endOfResp != null)
			endOfResp[0] = in_ptr;

		String r = bytesToString(buf);

		if (r.length() > 0)
			return true;
		else
			return false;
	}
	
	/**
	 * Converts a null-terminated array of bytes to a string.
	 * 
	 * @param buf
	 * @return
	 */
	static String bytesToString(byte[] buf){
		if(buf[0]==0) return "";
		int off = 0;
		for (int i = 0; i < buf.length && buf[i] != 0; i++)
			off = i;
		return new String(buf, 0, off+1);
	}

	@Override
	public String getInterfaceIdentifier() {
		return device.toString();
	}

}


enum ELMInterfaceType{
	INTERFACE_ELM320{public String toString(){return "ELM 320";}},
	INTERFACE_ELM322{public String toString(){return "ELM 322";}},
	INTERFACE_ELM323{public String toString(){return "ELM 323";}},
	INTERFACE_ELM327{public String toString(){return "ELM 327";}},
	UNKNOWN_INTERFACE
}

interface ELMResponse{
	void visit(ElmResponseVisitor visitor);
}
interface ElmResponseVisitor{

	Object dataError2();

	Object noData();

	Object interfaceFound(ELMInterfaceType type);

	Object unknownCommand();

	Object serialError();

	Object busInitError();

	Object bufferIsFull();

	Object busError();

	Object CANError();

	Object dataError();

	Object busBusy();

	Object unableToConnect();

	Object hexData();

	Object rubbish();
	
}

abstract class AElmResponseVisitor implements ElmResponseVisitor{
	public Object dataError2(){return defaultCase();}
	public Object interfaceFound(ELMInterfaceType type){return defaultCase();}
	public Object unknownCommand(){return defaultCase();}
	public Object serialError(){return defaultCase();}
	public Object busInitError(){return defaultCase();}
	public Object bufferIsFull(){return defaultCase();}
	public Object busError(){return defaultCase();}
	public Object CANError(){return defaultCase();}
	public Object dataError(){return defaultCase();}
	public Object busBusy(){return defaultCase();}
	public Object unableToConnect(){return defaultCase();}
	public Object hexData(){return defaultCase();}
	public Object noData(){return defaultCase();}
	public Object rubbish(){return defaultCase();}
	abstract Object defaultCase();
}

enum ELMResponseCode{
	//process_response return values
	HEX_DATA,
	BUS_BUSY,
	BUS_ERROR,
	BUS_INIT_ERROR,
	UNABLE_TO_CONNECT,
	CAN_ERROR,
	DATA_ERROR,
	DATA_ERROR2,
	ERR_NO_DATA,
	BUFFER_FULL,
	SERIAL_ERROR,
	UNKNOWN_CMD,
	RUBBISH,
	INTERFACE_ID,
	PROTOCOL_INIT_ERROR;
	
	public String toString(){ return getMessage(); }
	
	String getMessage(){
		return getMessage(this);
	}
	// Adapted from ScanTool
	public static String getMessage(ELMResponseCode error) {
		switch (error) {
		case BUS_ERROR:
			return "Bus Error: OBDII bus is shorted to Vbatt or Ground.";

		case BUS_BUSY:
			return "OBD Bus Busy. Try again.";

		case BUS_INIT_ERROR:
			return "OBD Bus Init Error. Check connection to the vehicle, make sure the vehicle is OBD-II compliant, and ignition is ON.";

		case UNABLE_TO_CONNECT:
			return "Unable to connect to OBD bus. Check connection to the vehicle. Make sure the vehicle is OBD-II compliant, and ignition is ON.";

		case CAN_ERROR:
			return "CAN Error. Check connection to the vehicle. Make sure the vehicle is OBD-II compliant, and ignition is ON.";

		case DATA_ERROR:
		case DATA_ERROR2:
			return "Data Error: there has been a loss of data. You may have a bad connection to the vehicle, check the cable and try again.";

		case BUFFER_FULL:
			return "Hardware data buffer overflow.";

		case SERIAL_ERROR:
		case UNKNOWN_CMD:
		case RUBBISH:
			return "Serial Link Error: please check connection between computer and scan tool.";
		default:
			return error.name();
		}
	}
}

enum ELMReadResult{
	EMPTY,
	DATA,
	PROMPT,
	TIMEOUT,
}
