package com.mf.btcbot;
//TODO: fix remove order with orderdepth <= 2
//TODO: stream fills



import java.awt.*;
import java.awt.event.*;
import java.applet.*;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Time;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.ConcurrentSkipListSet;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLSession;

import org.apache.commons.codec.binary.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.security.cert.X509Certificate;

import io.socket.*;


public class btcbot
extends Applet
implements ActionListener,Runnable
{
	// TODO: IMPORTANT: MAKE SURE (9/5)MARKETSPREAD - INTERVALLN > 2xcommission
	private static final long serialVersionUID = 1L;
	//TRADING CONSTANTS
	final static double FAIRVALUE = 38;
	final static double USDFRAC = .087;  // fraction of bankroll to keep in usds at fair value
	final static double INTERVALLN = .006; //ln(p1) - ln(p2) // order interval ( in % )
	final static double STDLN = 1; //ln(upper bound) - ln(lower bound) // standard deviation of market
	final static double STDPERCENTAGE = .6; //percentage of bankroll to commit per standard dev
	final static double MARKETSPREAD = .018; // market spread from fv to bid or ask/ multiply by 2 to get market width
	final static int ORDERDEPTH = 5;  //number of active bids and asks at a time
	double orderfrac; // fraction of br used per order -- 9.573643037118496E-4 w/ current values
	double fairvalueln;
	double evenptln; // point at which usd% = btc% in log scale
	final static int BID = 2;
	final static int ASK = 1;
	final static int WIDTH = 800;
	final static int HEIGHT = 750;
	final static int TXTROWS = 10;
	final static int TXTCOLUMNS = 80;
	final static int TRADEDELAY = 500000;  // time between cycles in ms
	final static int FULLUPDATEFREQ = 4; //number of trade update cycles before full update
	final static long MVDEPTH = (long) 7e11;  //depth to go to to ascertain current market value
	final static String VARSFILE = "vars.dat"; //stores last loaded tid
	final static String LOGFILE = "log.txt";
	OrdComparator ordcomp;
	ConcurrentSkipListSet<Order> bids;
	ConcurrentSkipListSet<Order> asks;
	ConcurrentSkipListSet<Order> mybids;
	ConcurrentSkipListSet<Order> myasks;
	double marketprice;
	double mpln;
	long usds; // usd in account * 10^5
	long btcs; // btc in account * 10^8
	long totalbr; //  = (long)(usds + (double)btcs * FAIRVALUE/1000);
	TextArea ta;
	TextArea orderstatus;
	TextArea recentorders;
	Button run;
	Button stop;
	Button conbtn;
	Button disconnect;
	Button unsubscribe;
	Button testbtn;
	JSONArray trades;
	boolean isStandAlone = false;
	final static String mtgoxsocketurl = "https://socketio.mtgox.com/socket.io/1/";
	final static String depth = "https://mtgox.com/api/1/BTCUSD/public/fulldepth";
	final static String closedepth = "https://mtgox.com/api/1/BTCUSD/public/depth?raw";
	final static String getorders = "https://mtgox.com/api/0/getOrders.php";         //gets orders and account balances
	final static String submitorder = "https://mtgox.com/api/1/BTCUSD/private/order/add";
	final static String cancelorder = "https://mtgox.com/api/0/cancelOrder.php";
	final static String tradesurl = "https://mtgox.com/api/1/generic/private/wallet/history";
	final static String idkeyurl = "https://mtgox.com/api/1/generic/private/idkey";
	String mtgoxSocketID = null;
	String idkey = "";
	Thread loader;
	long lasttid = 0;
	InputStream is;
	BufferedWriter out;
	final static String key = "xxxxxxxxxx";
	final static String secret = "xxxxxxxxxxxxx";
	SocketIO socket;
	
	public void actionPerformed(ActionEvent ev)
	{
		Object src = ev.getSource();
		if (src == run){
			getvars();
			loader = new Thread(this);
			loader.start();
		}
		else if (src == stop){
			loader = null;
		}
		else if (src == conbtn)
		{

			Connect();

			try {
				Thread.sleep(10000);
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}
			
		Unsubscribe();
			
		}
		else if (src == disconnect)
		{
			socket.disconnect();
		}
		else if (src == unsubscribe)
		{
			Unsubscribe();
		
			///////////////TEMP TEST CODE //////////////
			JSONObject json1 = new JSONObject();
			try {
				json1.put("op", "unsubscribe");
				json1.put("channel", "24e67e0d-1cad-4cc0-9e7a-f8523ef460fe"); //depth
			} catch (JSONException e) {
				e.printStackTrace();
			}
			/////////////////////////////////////////
			
		}
		else if (src == testbtn)
		{
			Connect();
			try {
				Thread.sleep(15000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Unsubscribe();
			JSONObject json1 = new JSONObject();
			try {
				json1.put("op", "unsubscribe");
				json1.put("channel", "24e67e0d-1cad-4cc0-9e7a-f8523ef460fe"); //depth
			} catch (JSONException e) {
				e.printStackTrace();
			}
			socket.send(json1);
			try {
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			SubscribePrivate();
			
		}
	}
	@Override
	public void run() {
		boolean loadrecent = true;
		ta.append("loader thread starting!!!\n");
		try {
			out.write("loader thread starting!!!");
			out.newLine();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		Thread thisthread = Thread.currentThread();

		if (loadclosedepth()){
			if (loadorders()){
				dostrat();
				if (loadrecent)
					Loadrecent();
				loadrecent = !loadrecent;
			/*	Connect();
				try {
					Thread.sleep(15000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				Unsubscribe();
				SubscribePrivate();*/
			}
		}
		try{
			out.flush();
			Thread.sleep(TRADEDELAY);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		while (loader == thisthread){
			if (loadclosedepth()){
				if (loadorders()){
					dostrat();
					if (loadrecent)
						Loadrecent();
					loadrecent = !loadrecent;
				/*	if (!socket.isConnected()){
						Connect();
						try {
							Thread.sleep(15000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
						Unsubscribe();
						SubscribePrivate();
					}*/
				}
			}
			try {
				out.flush();
				Thread.sleep(TRADEDELAY);
			} catch (InterruptedException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		try {
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		ta.append("loader thread stopped\n");
		try {
			out.write("loader thread stopped");
			out.newLine();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	private void Connect(){

		try {
			idkey = new JSONObject(authpost(idkeyurl, null)).getString("return");
		} catch (JSONException e) {
			e.printStackTrace();
		}
	
		try {
			socket = new SocketIO("https://socketio.mtgox.com/mtgox");
		} catch (MalformedURLException e1) {
			e1.printStackTrace();
		}
		disableCertificateValidation();
		socket.connect(new IOCallback() {
			@Override
			public void onMessage(JSONObject json, IOAcknowledge ack) {        		
				JSONObject json1;
				Iterator<Order> iter;
				String channel, s;
				Long price, p2 = null, volume;
				int type;
				Order o;
				try {
					//	ta.append("Server said in json:\n" + json.toString(2));
					channel = json.getString("channel");
					if (channel.equals("24e67e0d-1cad-4cc0-9e7a-f8523ef460fe")){  //depth update 
						json1 = json.getJSONObject("depth");
						if (json1.getString("currency").equals("USD")){
							type = json1.getInt("type");
							price = json1.getLong("price_int");
							if (type == 2){ //BID
								synchronized(this){
									iter = myasks.iterator();
									if (iter.hasNext()){
										o = iter.next();
										p2 = o.getPrice();
									}
									else
										o = null;
								}
								if (o != null){
									if (price >=  p2){ // our ask is filled
										out.write("Ask filled at " + p2 + "\n");
										out.flush();
										Thread.sleep(2000);
										while (!loadorders()){
											Thread.sleep(5000);
										}
									}
								}
								synchronized(this){
									iter = mybids.descendingIterator();
									if (iter.hasNext())
										p2 = iter.next().getPrice();
									else
										p2 = -1l;
								}
								if (p2 != -1l && price != p2 && price > p2){  //don't do anything if its your first order or > 1 order away

									volume = json1.getLong("volume_int");
									if (volume < 0) //need to remove order
										removeOrder(price, volume, type);
									else{
										addOrder(price, volume, type);
									}
									dostrat();

									//	else
									//		ta.append("Did not remove order - out of range.  price: " + price + " vol: " + json1.getLong("volume_int") + " type: " + type + "\n");
								}
								//	else
								//	ta.append("Did not remove order - own order.  price: " + price + " vol: " + json1.getLong("volume_int") + " type: " + type + "\n");
							}
							else{ //ask
								synchronized(this){
									iter = mybids.descendingIterator();
									if (iter.hasNext()){
										o = iter.next();  
										p2 = o.getPrice();
									}
									else
										o = null;
								}
								if (o != null){
									if (price <= p2){ // our bid is filled
										out.write("Bid filled at " + p2 + "\n");
										out.flush();
										Thread.sleep(2000);
										while (!loadorders()){
											Thread.sleep(5000);
										}
									}
								}
								synchronized(this){
									iter = myasks.iterator();
									if (iter.hasNext())
										p2 = iter.next().getPrice();
									else 
										p2 = -1l;
								}
								if (p2 != -1l && price != p2 && price < p2){  //don't do anything if its your first order or > 1 order away
									volume = json1.getLong("volume_int");
									if (volume < 0) //need to remove order
										removeOrder(price, volume, type);
									else{
										addOrder(price, volume, type);
									}
									dostrat();

									//	else
									//	ta.append("Did not remove order - out of range.  price: " + price + " vol: " + json1.getLong("volume_int") + " type: " + type + "\n");
								}
								//	else
								//	ta.append("Did not remove order - own order.  price: " + price + " vol: " + json1.getLong("volume_int") + " type: " + type + "\n");
							}
						}
					}
					else if (channel.equals("ece9431a-03bf-48eb-ab00-89e2146f496e")){ // private channel
						synchronized (this){
							s = json.getString("private");
							if (s == "wallet"){
								json1 = json.getJSONObject("wallet").getJSONObject("balance");
								if (json1.getString("currency") == "BTC")
									btcs = json1.getLong("value_int");
								else if (json1.getString("currency") == "USD")
									usds = json1.getLong("value_int");

							}
							else if (s == "trade")
							{
								out.write("Trade message.\n");
								out.flush();
								Thread.sleep(2000);
								while (!loadorders()){
									Thread.sleep(5000);
								}
								
							}
						}
					}
				} catch (JSONException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

			@Override
			public void onMessage(String data, IOAcknowledge ack) {
				ta.append("Server said in string: \n" + data);
			}

			@Override
			public void onError(SocketIOException socketIOException) {
				ta.append("an Error occured\n");
				try {
					out.write("an Error occured");
					out.newLine();
				} catch (IOException e){
					e.printStackTrace();
				}
				socket.disconnect();
				socketIOException.printStackTrace();
	        /*	try {
	        		Thread.sleep(30000);
	        	} catch (InterruptedException e1) {
	        		e1.printStackTrace();
	        	}
	        	ta.append("Reconnecting...\n");
	        	Connect();*/
	        }

	        @Override
	        public void onDisconnect() {
	            ta.append("Connection terminated.\n");
	            try {
					out.write("Connection terminated.");
					out.newLine();
				} catch (IOException e) {
					e.printStackTrace();
				}
	        }
	
	        @Override
	        public void onConnect() {
	            ta.append("Connection established\n");
	            try {
					out.write("Connection established");
					out.newLine();
				} catch (IOException e) {
					e.printStackTrace();
				}
	        }
	
	        @Override
	        public void on(String event, IOAcknowledge ack, Object... args) {
	            ta.append("Server triggered event '" + event + "'\n");
	        }
	    });      		
	}
	public btcbot()
	{
		loader = null;
		fairvalueln = Math.log(FAIRVALUE);
		orderfrac = 1.0 - (Math.pow((1.0 - STDPERCENTAGE), (INTERVALLN/STDLN))); //0.003658454416285608264189136245566
		evenptln = fairvalueln - STDLN * Math.log(.5/USDFRAC)/Math.log(1-STDPERCENTAGE);
		run = new Button("DO IT!!!");
		stop = new Button("STOP!!!");
		conbtn = new Button("connect");
		disconnect = new Button("disconnect");
		unsubscribe = new Button("unsubscribe");
		testbtn = new Button("test");
		ta = new TextArea(TXTROWS,TXTCOLUMNS);
		ta.setEditable(false);
		orderstatus = new TextArea(30,30);
		orderstatus.setEditable(false);
		recentorders = new TextArea(20,50);
		recentorders.setEditable(false);
		ordcomp = new OrdComparator();
		bids = new ConcurrentSkipListSet<Order>(ordcomp);
		asks = new ConcurrentSkipListSet<Order>(ordcomp);
		mybids = new ConcurrentSkipListSet<Order>(ordcomp);
		myasks = new ConcurrentSkipListSet<Order>(ordcomp);
		try {
			out = new BufferedWriter(new FileWriter(LOGFILE, false));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void	init ()
	{
		run.addActionListener(this);
		stop.addActionListener(this);
		conbtn.addActionListener(this);
		disconnect.addActionListener(this);
		unsubscribe.addActionListener(this);
		testbtn.addActionListener(this);
		add(run);
		add(stop);
		add(conbtn);
		add(disconnect);
		add(unsubscribe);
		add(testbtn);
		add(new Label("Status"));
		add(ta);
		add(new Label("Current Orders"));
		add(orderstatus);
		add(new Label("Recent Fills"));
		add(recentorders);
		validate();
	}

	public static void	main ( String[] args )
	{
		btcbot ap = new btcbot();
		ap.isStandAlone = true;

		OwnFrame frame = ap.new OwnFrame() ;
		frame.setTitle ( "btcbot" ) ;
		frame.setSize ( WIDTH, HEIGHT ) ;

		frame.add ( ap, BorderLayout.CENTER ) ;

		ap.init();

		Dimension scr =
			Toolkit.getDefaultToolkit().getScreenSize();
		int frw, frh ;
		frw = frame.getSize().width ;
		frh = frame.getSize().height ;
		frame.setLocation
		((scr.width - frw ) / 2,
				(scr.height - frh ) / 2);
		frame.setVisible(true);
	}
	private synchronized void addOrder(long price, long vol, int type)
	{
	//	ta.append("adding order.  price: " + (double)price/1e5 + " vol: " + (double)vol/1e8 + " type: " + type + "\n");
		if (type == 1)
			asks.add(new Order(price, vol, "add-stream"));
		else
			bids.add(new Order(price, vol, "add-stream"));
	}
	private synchronized void removeOrder(long price, long vol, int type){ // removes an order from the book, updates through the second order
		Order o;
		Order o2;
		Iterator<Order> iter;
		long p2;
	//	ta.append("removing order.  price: " + (double)price/1e5 + " vol: " + (double)vol/1e8 + " type: " + type + "\n");
		if (type == 1){
			o = new Order(price - 1, -1, "");
			o2 = asks.higher(o);
			if (o2 != null && o2.getPrice() == price){ // this is the order we're looking for
				if (o2.getVol() == -vol) //we can delete order
					asks.remove(o2);
				else if (o2.getVol() >= -vol) //we can subtract from order
					o2.setVol(vol + o2.getVol());
				else{ // not enough vol in order
					vol += o2.getVol();
					asks.remove(o2);
					asks.add(new Order(price, vol, "rm-stream"));
				}
			}
			else{ // couldn't find the right order in the book
				asks.add(new Order(price, vol, "rm-stream"));
			}
			iter = myasks.iterator();
			if (iter.hasNext()){
				iter.next();
				if (iter.hasNext()){
					p2 = iter.next().getPrice();
					iter = asks.iterator();
					while (iter.next().getPrice() < price){
						if (!iter.hasNext())
							break;
					}
					if (iter.hasNext()){
						while ((o = iter.next()).getPrice() < p2){
							o.setTotalVol(o.getTotalVol() + vol);
							if (!iter.hasNext())
								break;
						}
					}
				}
			}
		}
		else{ // bid
			o = new Order(price + 1, -1, "");
			o2 = bids.lower(o);
			if (o2 != null && o2.getPrice() == price){ // this is the order we're looking for
				if (o2.getVol() == -vol) //we can delete order
					bids.remove(o2);
				else if (o2.getVol() >= -vol) //we can subtract from order
					o2.setVol(vol + o2.getVol());
				else // not enough vol in order
					vol += o2.getVol();
					bids.remove(o2);
					bids.add(new Order(price, vol, "rm-stream"));
			}
			else{ // couldn't find the right order in the book
				bids.add(new Order(price, vol, "rm-stream"));
			}
			iter = mybids.descendingIterator();
			if (iter.hasNext()){
				iter.next();
				if (iter.hasNext()){
					p2 = iter.next().getPrice();
					iter = bids.descendingIterator();
					while (iter.next().getPrice() > price){
						if (!iter.hasNext())
							break;
					}
					if (iter.hasNext()){
						while ((o = iter.next()).getPrice() > p2){
							o.setTotalVol(o.getTotalVol() + vol); 
							if (!iter.hasNext())
								break;
						}
					}
				}
			}
		}
	}

	private synchronized boolean loadorders(){
		String response = "";
		mybids.clear();
		myasks.clear();
		ta.append("Loading orders...");
		try {
			out.write("Loading orders...");
			Order neworder;
			response = authpost(getorders, null);
			if (!response.equals(""))
			{
				JSONObject json = new JSONObject(response);
				JSONObject order = null;
				usds = (long)(json.getDouble("usds") * 100000);
				btcs = (long)(json.getDouble("btcs") * 100000000);
				totalbr = (long)((double)usds + (double)btcs * marketprice/1000.);
				JSONArray myorders = json.getJSONArray("orders");
				for (int j = 0; j < myorders.length(); j++){
					order = myorders.getJSONObject(j);
					neworder = new Order(order.getLong("price_int"), order.getLong("amount_int"), order.getString("oid"));
					if (order.getInt("type") == 2)   //bid
						mybids.add(neworder);
					else if (order.getInt("type") == 1)  //ask
						myasks.add(neworder);
				}
			}
			if (response != ""){
				ta.append("done.\n");
				out.write("done.");
				out.newLine();
				return true;
			}
			else{
				ta.append("FAILED.\n");
				out.write("FAILED.");
				out.newLine();
				return false;
			}
		} catch (JSONException e1) {
			e1.printStackTrace();
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	//type 2=bid, 1=ask
	private void cancelorder(String id, int type){
		String date = new Date().toString();
		try{
			out.write(date + " cancelling order - type: " + type + " id: " + id);
			out.newLine();
		} catch (IOException e) {
			e.printStackTrace();
		}
		ta.append(date + " cancelling order - type: " + type + " id: " + id + "\n");
		while (authpost(cancelorder, "oid="+id+"&type="+type) == "");
	}
	
	
	private ConcurrentSkipListSet<Order> copyOrders(ConcurrentSkipListSet<Order> input){
		Order o; 
		ConcurrentSkipListSet<Order> output = new ConcurrentSkipListSet<Order>();
		Iterator<Order> iter = input.iterator();
		while (iter.hasNext()){
			o = iter.next();
			output.add(new Order(o.getPrice(), o.getLogPrice(), o.getVol(), o.getTotalVol(), o.getStamp()));
		}
		return output;
	}

	@SuppressWarnings("unused")
	private synchronized void dostrat(){
	//	ta.append("strategizing...");
		
		double bpln,  // ln scale balanceprice
		bidsnum, asksnum, tempdelta, max, bidsbackup, asksbackup, usdpctgoal, usdpct; 
		long numorders;     // number of sell BTC/buy USD orders since btcs==usds point
		int ordersneeded;  // number of sell BTC orders needed to bring balaceprice in line with marketprice
		ConcurrentSkipListSet<Order> oldbids = copyOrders(mybids);
		ConcurrentSkipListSet<Order> oldasks = copyOrders(myasks);
		Order o, o2, o3, o4;
		long y1, y2, tempy, x1, x2, tempx, bestx, vol, l1;
		long bidsvol = 0;
		long asksvol = 0;
		long biddollars = 0;
		long askdollars = 0;
		Iterator<Order> iter;
		JSONObject json;
		String s, s1;
		
		
		mybids.clear();
		myasks.clear();
		
		usdpct = (double)usds/totalbr;
		
		if (mpln < evenptln)
			usdpctgoal = 0.5*Math.pow((1-STDPERCENTAGE),((evenptln - mpln)/STDLN));
		else
			usdpctgoal = 1 - 0.5*Math.pow((1-STDPERCENTAGE),((mpln - evenptln)/STDLN));
		
		/*//TESTCODE
		btcs = 70000000;   //80000000
		usds = 100000;     //100000
		totalbr = (long) ((double)usds + (double)btcs * FAIRVALUE/1000.);
		marketprice = 5;
		mpln = Math.log(marketprice);
		//ENDTESTCODE*/

		numorders = (long)-(Math.log(2*usdpct)/Math.log(1-orderfrac));
			
		bpln = evenptln + INTERVALLN * numorders;
		ordersneeded = (int) -(Math.log(usdpctgoal/usdpct)/Math.log(1-orderfrac));


		if (ordersneeded < 0) //market is lower; buy some coins!!!
		{
			bidsnum = mpln;
			asksnum = bpln;
		}
		else if (ordersneeded > 0){
			bidsnum = bpln;
			asksnum = mpln;
		}
		else{
			bidsnum = mpln;
			asksnum = mpln;
		}


		if (ordersneeded < 11 && ordersneeded > -11){ 
			bidsbackup = 1 - MARKETSPREAD*(ordersneeded + 10)/10;
			asksbackup = 1 + MARKETSPREAD*(10-ordersneeded)/10;
		}
		else{
			if (ordersneeded < -10)
				bidsbackup = 1;
			else
				bidsbackup = 1 - 2*MARKETSPREAD;
			if (ordersneeded > 10)
				asksbackup = 1;
			else
				asksbackup = 1 + 2*MARKETSPREAD;
		}

		
		for (int i = 0; i < ORDERDEPTH; i++){
			
			// ---------------BIDS-------------	
			
			x1 = (long) (Math.exp(bidsnum - INTERVALLN * i)*100000*bidsbackup);
			x2 = (long) (Math.exp(bidsnum - INTERVALLN * (i+1))*100000*bidsbackup);
			o = new Order(x1, 0, "");
			o2 = new Order(x2, 0, "");
			o4 = asks.floor(o);
			if (i == 0 && o4 != null && ordersneeded < 0 && o4.getTotalVol() >= 1000000){	// HIT THE OFFER!!
				vol = (long)Math.min(-btcstosell(usds/1e5, btcs/1e8, x1/1e5),o4.getTotalVol());
				mybids.add(new Order(x1, vol ,""));
				bidsvol += vol;
				l1 = (long)(x1*vol/1e8);
				biddollars -= l1;
				ta.append("HITTING THE OFFERS AT $" + x1/1e5 + " for " + vol/1e8 + " BTCS\n");
				usds -= l1;
				btcs += vol;
			}
			else if (bids.ceiling(o2) != null){ // if there are some bids in front of the order one back
				if (i != 0 && !(bids.ceiling(o) == null))
					y1 = bids.ceiling(o).getTotalVol();
				else
					y1 = 0;
				y2 = bids.ceiling(o2).getTotalVol();
				max = 0;
				bestx = x2;
				o3 = bids.floor(o);
				while (o3 != null && o3.getPrice() >= o2.getPrice())
				{
					if (bids.higher(o3) != null)
						tempy = bids.higher(o3).getTotalVol();
					else
						tempy = 0;
					tempx = o3.getPrice() + 1;
					tempdelta = y1 + (double)(x1-tempx)/(x1-x2)*(y2-y1) - tempy;
					if (tempdelta > max){
						bestx = tempx;
						max = tempdelta;
					}
					o3 = bids.lower(o3);
				}

				vol = (long) -btcstosell((usds+biddollars)/1e5, (btcs + bidsvol)/1e8, bestx/1e5);
				if (vol >= 1000000){
					bidsvol += vol;
					biddollars -= (long)(bestx*vol/1e8); 
					mybids.add(new Order(bestx, vol ,"newbid"));
				}
			}

			//-------------OFFERS------------

			x1 = (long) (Math.exp(asksnum + INTERVALLN * i)*100000*asksbackup);
			x2 = (long) (Math.exp(asksnum + INTERVALLN * (i+1))*100000*asksbackup);
			o = new Order(x1, 0,"");
			o2 = new Order(x2, 0,"");
			o4 = bids.ceiling(o);
			if (i == 0 && o4 != null && ordersneeded > 0 && o4.getTotalVol() >= 1000000){ // HIT THE BID!!
				vol = (long)Math.min(btcstosell(usds/1e5, btcs/1e8, x1/1e5),o4.getTotalVol());
				myasks.add(new Order(x1, vol ,""));
				asksvol += vol;
				l1 = (long)(x1*vol/1e8);
				askdollars += l1;
				ta.append("HITTING THE BIDS AT $" + x1/1e5 + " for " + vol/1e8 + " BTCS\n");
				vol = 0;
				usds += l1;
				btcs -= vol;
			}
			else if (asks.floor(o2) != null){ // if there are some bids in front of the order one back
				if (i != 0 && asks.floor(o) != null)
					y1 = asks.floor(o).getTotalVol();
				else
					y1 = 0;
				y2 = asks.floor(o2).getTotalVol();
				max = 0;
				bestx = x2;
				o3 = asks.ceiling(o);
				while (o3 != null && o3.getPrice() <= o2.getPrice())
				{
					if (asks.lower(o3) != null)
						tempy = asks.lower(o3).getTotalVol();
					else
						tempy = 0;
					tempx = o3.getPrice() - 1;
					tempdelta = y1 + (double)(x1-tempx)/(x1-x2)*(y2-y1) - tempy;
					if (tempdelta > max){
						bestx = tempx;
						max = tempdelta;
					}
					o3 = asks.higher(o3);
				}
				vol = (long) btcstosell((usds+askdollars)/1e5, (btcs - asksvol)/1e8, bestx/1e5);
				if (vol >= 1000000){
					asksvol += vol;
					askdollars += (long)(bestx*vol/1e8); 
					myasks.add(new Order(bestx, vol ,"newask"));
				}
			}
		}
		iter = oldbids.iterator();
		while (iter.hasNext()){
			o = iter.next();
			if (!mybids.contains(o))
				cancelorder(o.getStamp(), BID);
		}
		iter = oldasks.iterator();
		while (iter.hasNext()){
			o = iter.next();
			if (!myasks.contains(o))
				cancelorder(o.getStamp(), ASK);
		}
		s = "---Bids---\n";
		iter = mybids.iterator();
		while (iter.hasNext()){
			o = iter.next();
			s += "price: " + o.getPrice()/1e5 + " vol: " + o.getVol()/1e8 + "\n";
			if (!oldbids.contains(o)){
				json = submitorder(BID, o.getVol(), o.getPrice());
				try {
					s1 = json.getString("return");
					o.setStamp(s1);
				} catch (JSONException e) {
					o.setStamp("JSON SUBMIT BID ORDER ERROR");
					e.printStackTrace();
				}
		
			}
			else o.setStamp(oldbids.floor(o).getStamp());
		}
		s += "\n---Offers---\n";
		iter = myasks.iterator();
		while (iter.hasNext()){
			o = iter.next();
			s += "price: " + o.getPrice()/1e5 + " vol: " + o.getVol()/1e8 + "\n";
			if (!oldasks.contains(o)){
				json = submitorder(ASK, o.getVol(), o.getPrice());
				try {
					s1 = json.getString("return");
					o.setStamp(s1);
				} catch (JSONException e) {
					o.setStamp("JSON SUBMIT ASK ORDER ERROR");
					e.printStackTrace();
				}
			}
			else o.setStamp(oldasks.floor(o).getStamp());
		}
		s += "\nsellBTC orders needed: " + ordersneeded + "\nusds: " + (double)usds/1e5 + "\nbtcs: " + (double)btcs/1e8 + "\nmarketprice: " + marketprice;	
		orderstatus.setText(s);
	}
	private long btcstosell(double dollars, double coins, double price){ //dollars and coins in decimal format
		double usdpctgoal;
		
		if (Math.log(price) < evenptln)
			usdpctgoal = 0.5 * Math.pow((1-STDPERCENTAGE),((evenptln-Math.log(price))/STDLN));
		else
			usdpctgoal = 1 -  0.5 * Math.pow((1-STDPERCENTAGE),((Math.log(price)-evenptln)/STDLN));
		
		return (long)((usdpctgoal*(dollars + coins*price) - dollars)/price*1e8);
	}

	@SuppressWarnings("unused")
	private synchronized boolean loadfulldepth(){
		JSONObject goxdepth = null;
		JSONObject json1 = null;
		JSONArray jsonarr;
		Iterator<Order> iter;
		Order o;
		double p1, p2;
		long totalvol;
		ta.append("trying to load full depth...\n");
		try {
			goxdepth = readJsonFromUrl(depth);
		} catch (JSONException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			if (goxdepth != null)
				json1 = goxdepth.getJSONObject("return");
		} catch (JSONException e) {
			e.printStackTrace();
		}

		if (json1 != null){
			try {
				bids.clear();
				asks.clear();
				jsonarr = json1.getJSONArray("bids");
				for (int i = 0; i < jsonarr.length(); i++)
					bids.add(new Order(jsonarr.getJSONObject(i).getLong("price_int"), jsonarr.getJSONObject(i).getLong("amount_int"), jsonarr.getJSONObject(i).getString("stamp")));
				jsonarr = json1.getJSONArray("asks");
				for (int i = 0; i < jsonarr.length(); i++)
					asks.add(new Order(jsonarr.getJSONObject(i).getLong("price_int"), jsonarr.getJSONObject(i).getLong("amount_int"), jsonarr.getJSONObject(i).getString("stamp")));
				iter = bids.descendingIterator();
				o = iter.next();
				totalvol = o.getVol();
				o.setTotalVol(totalvol);
				for (int i = 2; i <= bids.size(); i++){
					o = iter.next();
					totalvol += o.getVol();
					o.setTotalVol(totalvol);
				}
				iter = asks.iterator();
				o = iter.next();
				o.setTotalVol(o.getVol());
				totalvol = o.getVol();
				for (int i = 1; i < asks.size(); i++){
					o = iter.next();
					totalvol += o.getVol();
					o.setTotalVol(totalvol);
				}
				o = bids.last();
				while (o.getTotalVol() < MVDEPTH)
					o = bids.lower(o);
				p1 = o.getLogPrice();
				o = asks.first();
				while (o.getTotalVol() < MVDEPTH)
					o = asks.higher(o);
				p2 = o.getLogPrice();
				mpln = (p1 + p2)/2 - 11.512925464970228420089957273422; // need to subract const becasue p1/p2 are in usd*10^5
				marketprice = Math.exp(mpln);
			} catch (JSONException e1) {
				e1.printStackTrace();
			}
			String time = new Time(System.currentTimeMillis()).toString();
			ta.append(time + " full depth loaded successfully. bids:" + bids.size() + " asks:" + asks.size() + " marketprice:" + marketprice + "\n");
			return true;
		}
		else{
			ta.append("error loading full depth\n");
			return false;
		}
	}
	private synchronized boolean loadclosedepth(){
		JSONObject json1 = null;
		JSONArray jsonarr;
		Iterator<Order> iter;
		Order o, o2;
		double p1, p2;
		long totalvol;
		ta.append("trying to load close depth...\n");
		try {
			out.write("trying to load close depth...");
			out.newLine();
			json1 = readJsonFromUrl(closedepth);
		} catch (JSONException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		if (json1 != null){
			try {
				bids.clear();
				asks.clear();
				jsonarr = json1.getJSONArray("bids");
				for (int i = 0; i < jsonarr.length(); i++)
					bids.add(new Order(jsonarr.getJSONObject(i).getLong("price_int"), jsonarr.getJSONObject(i).getLong("amount_int"), jsonarr.getJSONObject(i).getString("stamp")));
				jsonarr = json1.getJSONArray("asks");
				for (int i = 0; i < jsonarr.length(); i++)
					asks.add(new Order(jsonarr.getJSONObject(i).getLong("price_int"), jsonarr.getJSONObject(i).getLong("amount_int"), jsonarr.getJSONObject(i).getString("stamp")));
				iter = bids.descendingIterator();
				o = iter.next();
				totalvol = o.getVol();
				o.setTotalVol(totalvol);
				for (int i = 2; i <= bids.size(); i++){
					o = iter.next();
					totalvol += o.getVol();
					o.setTotalVol(totalvol);
				}
				iter = asks.iterator();
				o = iter.next();
				o.setTotalVol(o.getVol());
				totalvol = o.getVol();
				for (int i = 1; i < asks.size(); i++){
					o = iter.next();
					totalvol += o.getVol();
					o.setTotalVol(totalvol);
				}
				o = bids.last();
				while (o.getTotalVol() < MVDEPTH){
					o2 = bids.lower(o);
					if (o2 != null)
						o = o2;
					else break;
				}
				p1 = o.getLogPrice();
				o = asks.first();
				while (o.getTotalVol() < MVDEPTH){
					o2 = asks.higher(o);
					if (o2 != null)
						o = o2;
					else break;
				}
				p2 = o.getLogPrice();
				mpln = (p1 + p2)/2 - 11.512925464970228420089957273422; // need to subract const becasue p1/p2 are in usd*10^5
				marketprice = Math.exp(mpln);
			} catch (JSONException e1) {
				e1.printStackTrace();
			}
			String time = new Time(System.currentTimeMillis()).toString();
			ta.append(time + " - close depth loaded successfully. bids:" + bids.size() + " asks:" + asks.size() + " marketprice:" + marketprice + "\n");
			try {
				out.write(time + " - close depth loaded successfully. bids:" + bids.size() + " asks:" + asks.size() + " marketprice:" + marketprice);
				out.newLine();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return true;
		}
		else{
			String time = new Time(System.currentTimeMillis()).toString();
			ta.append(time + " - error loading close depth\n");
			try {
				out.write(time + " - error loading close depth");
				out.newLine();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return false;
		}
	}
	//trade type: bid means new order hit an ask in the book e.g.: new order is a buy at market order
	private void loadtrades() throws IOException, JSONException{
		ta.append("trying to load trades...\n");
		if (lasttid == 0){
			is = new URL("https://mtgox.com/code/data/getTrades.php").openStream();}
		else{
			is = new URL("https://mtgox.com/code/data/getTrades.php?since="+lasttid).openStream();}
		JSONTokener tradetoken = new JSONTokener(is);
		trades = new JSONArray(tradetoken);
		if (trades.length() > 0)
			lasttid = trades.getJSONObject(trades.length() -1).getLong("tid");
		ta.append(trades.length() + " trades loaded successfully!\nlast tid:" + lasttid +"\n");		
		is.close();

		//TODO: do something with these trades...
	}
	private void getvars()
	{
		String workingDir = System.getProperty("user.dir");
		File file = new File(workingDir, VARSFILE);
		try {
			file.createNewFile();
			FileInputStream fis = new FileInputStream(file);
			DataInputStream dis = new DataInputStream(fis);
			lasttid = dis.readLong();
			dis.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	private void savevars()
	{
		String workingDir = System.getProperty("user.dir");
		File file = new File(workingDir, VARSFILE);
		try {
			FileOutputStream fos = new FileOutputStream(file);
			DataOutputStream dos = new DataOutputStream(fos);
			dos.writeLong(lasttid);
			dos.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
    //posts authenticated message to api and returns response
	private String authpost(String url, String args)
	{
		Base64 allyourbase = new Base64();
		String sig;
		BufferedReader in;
		URL theURL = null;
		String input = "";
		String postbody = null;
		HttpURLConnection httpcon = null;

		try {

			postbody = "nonce=" + nonce();
			if (args != null)
				if (!args.equals(""))
					postbody += "&" + args;
			theURL = new URL(url);
			sig = computeSignature("HmacSHA512", postbody, allyourbase.decode(secret));
			httpcon = (HttpURLConnection)theURL.openConnection();
			httpcon.setRequestMethod("POST");
			httpcon.setRequestProperty("Content-Type", 
			"application/x-www-form-urlencoded");

			httpcon.setRequestProperty("Content-Length", "" + 
					Integer.toString(postbody.getBytes().length));
			httpcon.setRequestProperty("Content-Language", "en-US");
			httpcon.setRequestProperty("User-Agent", "GoxApi");
			httpcon.setRequestProperty("Rest-Key", key);
			httpcon.setRequestProperty("Rest-Sign", sig);
			httpcon.setUseCaches (false);
			httpcon.setDoInput(true);
			httpcon.setDoOutput(true);
		//	ta.append("here is the post body: " + postbody + "\n");
		//	ta.append("here are the request properties: " + httpcon.getRequestProperties().values().toString() + "\n");

			//Send request
			DataOutputStream wr = new DataOutputStream (
					httpcon.getOutputStream ());
			wr.writeBytes (postbody);
			wr.flush ();
			wr.close ();
			in = new BufferedReader(
					new InputStreamReader(
							httpcon.getInputStream())); //TODO:: Program halt here with no connection

			StringBuffer response = new StringBuffer();
			while((input = in.readLine()) != null){
				response.append(input);
				response.append('\r');
			}
			in.close();
			input = response.toString();
			//ta.append("read " + input + " from " + url + "\n");


		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (GeneralSecurityException e) {
			e.printStackTrace();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}finally
		{
			if (httpcon != null)
				httpcon.disconnect();
		}
		if (input == null)
			input = "";
		return input;

	}
	private String nonce(){
		return "" + (System.currentTimeMillis());
	}

	public static String computeSignature(String algorithm, String baseString, byte[] key)
	throws GeneralSecurityException, UnsupportedEncodingException {
		SecretKey secretKey = null;
		secretKey = new SecretKeySpec(key, algorithm);
		Mac mac = Mac.getInstance(algorithm);
		mac.init(secretKey);
		mac.update(baseString.getBytes());
		return new String(Base64.encodeBase64(mac.doFinal())).trim();
	}
	private static String readAll(Reader rd) throws IOException {
		StringBuilder sb = new StringBuilder();
		int cp;
		while ((cp = rd.read()) != -1) {
			sb.append((char) cp);
		}
		return sb.toString();
	}
	public static JSONObject readJsonFromUrl(String url) throws IOException, JSONException {
		InputStream is = new URL(url).openStream();
		try {
			BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
			String jsonText = readAll(rd);
			JSONObject json = new JSONObject(jsonText);
			return json;
		} finally {
			is.close();
		}
	}
	//type 2=bid, 1=ask.  returns response vol * 10^8 price * 10^5
	private JSONObject submitorder(int type, long vol, long price){
		String date = new Date().toString();
		JSONObject json1 = null;
		if (vol >= 1000000){
			try{
				out.write(date + " submitting order - type: " + type + " volume: " + vol/1e8 + " price: " + price/1e5);
				out.newLine();
			} catch (IOException e) {
				e.printStackTrace();
			}
			ta.append(date + " submitting order - type: " + type + " volume: " + vol/1e8 + " price: " + price/1e5 + "\n");
			String response = "";
			if (type == 2){
				while (response.equals("")){
					response = authpost(submitorder, "type=bid&amount_int="+vol+"&price_int="+price);
				}
			}
			else if (type == 1){
				while (response.equals("")){
					response = authpost(submitorder, "type=ask&amount_int="+vol+"&price_int="+price);
				}
			}
			try {
				json1 = new JSONObject(response);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		else if (vol >= 0){
			try {
				json1 = new JSONObject("{'result':'FAIL', 'reason':'vol too low'}");
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		else{
			try {
				json1 = new JSONObject("{'result':'FAIL', 'reason':'negative vol'}");
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		return json1;
	}
	private void Loadrecent(){
		String response = "";
		String s = "";
		String s2 = "";
		String s3 = "";
		response = authpost(tradesurl, "currency=BTC");//&date_start="+(System.currentTimeMillis()/1000 - 86400)); //trades from the last 24h
		if (!response.equals("") && response != null)
		{
			try {
				JSONObject json1 = new JSONObject(response);
				JSONObject json3;
				json1 = json1.getJSONObject("return");
				JSONArray json2 = json1.getJSONArray("result");
				
				for (int i = 0; i < json2.length(); i++){
					json3 = (JSONObject)json2.get(i);
					s2 = json3.getString("Info");
					s3 = (new Date(((long)(Integer)((JSONObject)json2.get(i)).get("Date"))*1000)).toString();
					s += s3.substring(4, 19) + " ";
					if (s2.charAt(1) == 'T')
						s += s2.substring(s2.indexOf('C')+2, s2.indexOf('[')-1) + s2.substring(s2.indexOf(']')+1, s2.length()-1) + " \n";
					else
						s += s2 + " \n";
						
				}
				if (!s.equals(""))
					recentorders.setText(s);
				
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
	}
	private void Unsubscribe(){
		JSONObject json1 = new JSONObject();
		try {
			json1.put("op", "unsubscribe");
			json1.put("channel", "d5f06780-30a8-4a48-a2f8-7ed181b4a13f"); //ticker
		} catch (JSONException e) {
			e.printStackTrace();
		}
		socket.send(json1);
		
		json1 = new JSONObject();
		try {
			json1.put("op", "unsubscribe");
			json1.put("channel", "dbf1dee9-4f2e-4a08-8cb7-748919a71b21"); //trades
		} catch (JSONException e) {
			e.printStackTrace();
		}
		socket.send(json1);
	}
	private void SubscribePrivate(){
		JSONObject json1 = new JSONObject();
		try {
			json1.put("op", "mtgox.subscribe");
			json1.put("key", idkey); //ticker
		} catch (JSONException e) {
			e.printStackTrace();
		}
		socket.send(json1);
	}
	class OwnFrame
	extends Frame
	{
		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		OwnFrame ()	{
			addWindowListener( new WindowAdapter() {
				public void windowClosing ( WindowEvent we ) {
					savevars();
					dispose ();
					System.exit(0);
				}
			} );
		}
	}
	public static void disableCertificateValidation() { //TODO:  obv this is bad to disable SSL
		  // Create a trust manager that does not validate certificate chains
		  TrustManager[] trustAllCerts = new TrustManager[] { 
		    new X509TrustManager() {
		      public X509Certificate[] getAcceptedIssuers() { 
		        return new X509Certificate[0]; 
		      }
		      public void checkClientTrusted(X509Certificate[] certs, String authType) {}
		      public void checkServerTrusted(X509Certificate[] certs, String authType) {}
		  }};

		  // Ignore differences between given hostname and certificate hostname
		  HostnameVerifier hv = new HostnameVerifier() {
		    public boolean verify(String hostname, SSLSession session) { return true; }
		  };

		  // Install the all-trusting trust manager
		  try {
			SSLContext sc = SSLContext.getInstance("SSL");
		    sc.init(null, trustAllCerts, new SecureRandom());
		    SocketIO.setDefaultSSLSocketFactory(sc.getSocketFactory());
		    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
		    HttpsURLConnection.setDefaultHostnameVerifier(hv);
		  } catch (Exception e) {}
		}
}
class Order implements Comparable<Order>{
	private long price; // in usds x 10^5
	private double logprice;  // Math.log(price) - retuns natural log 
	private long vol; // in btcs x 10^8
	private long totalvol; // in btcs x 10^8
	private String stamp;
	Order(){
		this(0, 0, "");
	}
	Order(long newprice, long newvol, String newstamp){
		price = newprice;
		logprice = Math.log(price);
		vol = newvol;
		stamp = newstamp;
	}
	Order(long newprice, double newlogprice, long newvol, String newstamp){
		price = newprice;
		logprice = newlogprice;
		vol = newvol;
		stamp = newstamp;
	}
	Order(long newprice, double newlogprice, long newvol, long newtotalvol, String newstamp){
		price = newprice;
		logprice = newlogprice;
		vol = newvol;
		stamp = newstamp;
		totalvol = newtotalvol;
	}
	
	long getPrice(){
		return price;
	}
	
	double getLogPrice(){
		return logprice;
	}
	
	long getVol(){
		return vol;
	}
	
	long getTotalVol(){
		return totalvol;
	}
	
	String getStamp(){
		return stamp;
	}
	
	void setPrice(long price){
		this.price = price;
		this.logprice = Math.log(price);
	}
	
	void setLogPrice(double logprice){
		this.logprice = logprice;
		this.price = (long) Math.exp(logprice);
	}
	
	void setVol(long vol){
		this.vol = vol;
	}
	
	void setTotalVol(long totalvol){
		this.totalvol = totalvol;
	}
	
	void setStamp(String stamp){
		this.stamp = stamp;
	}
	
	
	
	@Override
	public int compareTo(Order o) {
		if (price < o.price)
			return -1;
		else if (price > o.price)
			return 1;
		else{
			if (vol < o.vol)
				return -1;
			else if (vol > o.vol)
				return 1;
			else
				return 0;
		}
	}
}
class OrdComparator implements Serializable, Comparator<Order>{
	private static final long serialVersionUID = 1L;
	@Override
	public int compare(Order o1, Order o2) {
		if (o1.getPrice() < o2.getPrice())
			return -1;
		else if (o1.getPrice() > o2.getPrice())
			return 1;
		else{
			if (o1.getVol() < o2.getVol())
				return -1;
			else if (o1.getVol() > o2.getVol())
				return 1;
			else
				return 0;
		}
	}	
}