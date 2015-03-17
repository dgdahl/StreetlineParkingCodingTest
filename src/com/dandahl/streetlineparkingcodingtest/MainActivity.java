package com.dandahl.streetlineparkingcodingtest;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.json.JSONException;
import org.json.JSONObject;

import android.os.Bundle;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.JsonReader;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ViewSwitcher;
import android.util.JsonReader;

/*
 * This file is the sole java source file for Dan Dahl's effort for the Streetline coding exercise.
 * The app runs, with the following functionality:
 * Parses the static data from the included JSON file
 *  - Builds a list of spaces
 *  - Builds a list of parking zones of type "block" (all others discarded)
 * Fetches updated parking space info every 8 seconds
 * Displays list of parking zones including Title and id.
 * Displays number of available spaces in that zone when the user taps it.
 *  ///SHOULD/// update number of spaces live if the user is viewing details for a zone, but I never saw one change;
 *     I guess everyone in LA was where they wanted to be this Sunday morning.
 * 
 * There are a number of custom internal classes here. I tired to put them all at the end of the file. If you see
 * a class that you've never heard of, it's likely defined down there. * 
 */

public class MainActivity extends Activity {

	String realTimeParkingData = new String(); // Given the nature of the task, indexing chars in a string works to determine if a space is free
	private ListView blockListView; // The scrolling list to show each parking zone of type block
	StaticParkingData staticParkingData; // Parking space id's and zone info doesn't change, hence it's put in this class
	private ScheduledExecutorService scheduleTaskExecutor; // This task fetches updated availability info every 8 seconds
	ViewSwitcher viewSwitcher; // Given the tast, I decided to just switch between two views (block list and space info)
	int viewedBlock = 0; // I don't like doing things this way, but I ran out of time. This holds the index of the block being viewed for live update purposes.
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_streetline_main);
		viewSwitcher = (ViewSwitcher) findViewById(R.id.viewSwitcher);
		staticParkingData = getStaticParkingDataFromJSON();
		setupScheduler();
		blockListView = (ListView) findViewById(R.id.listView1);
		
		// Make a custome array adapter for our list view, then add a clickHandler for it.
		ParkingArrayAdapter parkingArrayAdapter = new ParkingArrayAdapter(getApplicationContext(), staticParkingData.getSpaceGroups());
		blockListView.setAdapter(parkingArrayAdapter); 
		
		blockListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
	         @Override
	         public void onItemClick(AdapterView<?> a, View v, int position, long id) { 
	          Object o = blockListView.getItemAtPosition(position);
	          SpaceGroup fullObject = (SpaceGroup)o;
	          updateParkingSpaceView(fullObject);
	          viewedBlock = position;
	          viewSwitcher.showNext();
	          }  
	        });
	}

	public void onBackBtnClicked(View v){ // The back button in question is on the detail parking space view (returns user to list view)
		viewSwitcher.showPrevious();
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.streetline_main, menu);
		return true;
	}
	
	@Override
	public void onPause()
	{
		// Tear down scheduler and its http fetches when we pause
		scheduleTaskExecutor.shutdown();
		super.onPause();
	}

	@Override
	public void onResume()
	{
		setupScheduler();
		super.onResume();
	}

	/*
	 * This method fills in the TextViews for detailed info regarding a block's space availability.
	 * We use the parkindSpaceIds of the block to index (less 1 for array purposes) into
	 * the realtime string info and add up how many "1"'s we get.
	 */
	private void updateParkingSpaceView(SpaceGroup sg)
	{
		int spacesAvailable = 0;
		int spaceId;
		TextView blockTitle = (TextView) findViewById(R.id.blockTitle);
		TextView blockSpaceAvailable = (TextView) findViewById(R.id.blockSpaceAvailable);
		ArrayList psIds = sg.getParkingSpaceIds();
		Iterator it = psIds.iterator();
		
		while(it.hasNext())
		{
			spaceId = (new Integer((Integer)it.next()).intValue());
			spacesAvailable += ((realTimeParkingData.substring(spaceId - 1, spaceId)).equals("1")) ? 1 : 0;
		}
		blockTitle.setText(sg.getTitle());
		blockSpaceAvailable.setText("There are " + spacesAvailable + " parking spaces available in " + sg.getTitle());
	}
	
	private void updateUIParkingInfo(String availStrTemp)
	{
		realTimeParkingData = availStrTemp;
		
		if(findViewById(R.id.parkingSpaceView1).isShown())
		{
			SpaceGroup sg = (SpaceGroup) blockListView.getItemAtPosition(viewedBlock);
			Log.d("updateUIParkingInfo","view visible, updating.");
			updateParkingSpaceView(sg);
		}
	}
	
	/*
	 * Create a thread to fetch the parking info at regular intervals, so we don't block the UI Main thread.
	 * The interval value should be abstracted to be more configurable.
	 */
	private void setupScheduler()
	{	
		scheduleTaskExecutor = Executors.newScheduledThreadPool(2);
		scheduleTaskExecutor.scheduleAtFixedRate(new Runnable() {
		public void run() {
			String parkingDataTemp = doRealTimeParkingFetch();
			final String availStrTemp = getAvailableParkingDataStr(parkingDataTemp);
			runOnUiThread(new Runnable() {
				public void run() {
            // refresh UI
					updateUIParkingInfo(availStrTemp);
				}
			});
		}
		}, 0, 8, TimeUnit.SECONDS);
	}

	/*
	 * Although I initially planned to make use of JSON objects, I realized I could just keep the string portion of
	 * the http response that contained the actual space info and use it.
	 * This routine is easily broken if names/format of the JSON change at all, and I would not put this into production.
	 */
	String getAvailableParkingDataStr(String parkingDataTemp)
	{
		String returnStr = new String("");
		String beginIndex = "availabilityBitsDecoded";
		returnStr = parkingDataTemp.substring(parkingDataTemp.indexOf(beginIndex) + beginIndex.length() + "\":\"".length());
		returnStr = returnStr.substring(0, returnStr.indexOf("\""));
		return returnStr;		
	}
	
	/*
	 * Do our fetch of actual data. Nothing special here, just a fetch and return.
	 */
	private String doRealTimeParkingFetch()
	{
		
	    try {
	    	URL url = new URL(getString(R.string.traffic_url));
	        HttpURLConnection htc = (HttpURLConnection) url.openConnection();
	        htc.setRequestMethod("GET");
	        htc.setRequestProperty("Content-length", "0");
	        htc.setUseCaches(false);
	        htc.setAllowUserInteraction(false);
	        htc.setConnectTimeout(2000);
	        htc.setReadTimeout(4000);
	        htc.connect();
	        int status = htc.getResponseCode();

	        switch (status) {
	            case 200:
	            case 201:
	                BufferedReader br = new BufferedReader(new InputStreamReader(htc.getInputStream()));
	                StringBuilder sb = new StringBuilder();
	                String line;
	                while ((line = br.readLine()) != null) {
	                    sb.append(line);
	                }
	                br.close();
	                // Uncomment to verify proper fetching of rt parking data
	                Log.d("doRealTimeParkingFetch", sb.toString());
	                return sb.toString();
	        }
	    } catch (MalformedURLException e) {
	    	Log.e("MalformedURLException", "Error: " + e.toString());
	    } catch (IOException e) {
	    	Log.e("IOException", "Error: " + e.toString());
        } catch (Exception e) {
            Log.e("Exception", "Error: " + e.toString());
	    }
	    return null;	
	}
	
	/*
	 * This caused me far more grief than I expected. I have dealt with large streams of xml before, but never
	 * JSON big enough to make android puke. This routine and its support routines does not make use of gzipped 
	 * data. That would be the next step here:
	 *  - Read gzipped data into buffer
	 *  - Use JSONReader to parse some of it, keeping track of how much is left in the buffer
	 *  - When it gets low enough, copy remaining data to beginning of buffer and fetch more.
	 *  - Repeat until done
	 */
	private StaticParkingData getStaticParkingDataFromJSON()
	{
		InputStream is;
		StaticParkingData spd = new StaticParkingData();
	    try {
			is = getResources().getAssets().open("la.json");
			JsonReader reader = new JsonReader(new InputStreamReader(is, "UTF-8"));
			spd = readParkingJSON(reader);  
			reader.close();
	    } catch (UnsupportedEncodingException e) {
            Log.e("Encoding error", "Error: " + e.toString());
        } catch (IOException e) {
            Log.e("IOException", "Error: " + e.toString());
        } catch (Exception e) {
            Log.e("Exception", "Error: " + e.toString());
        }
	    return spd;
	}
	
	/*
	 * This ugly method id a candidate for refactoring to fetch the space ids and block info separately. Ran out of time.
	 */
	public StaticParkingData readParkingJSON(JsonReader reader) throws IOException {
		 ArrayList<Space> spaces = new ArrayList();
		 ArrayList<SpaceGroup> spaceGroups = new ArrayList();
		
		 reader.beginObject();
		 String name = reader.nextName();
		 reader.beginObject();
		 name = reader.nextName();
		 reader.beginArray();
		 while (reader.hasNext()) {
			 if(reader.peek().toString().equals("NULL"))
			 {
				 reader.skipValue();
				 spaces.add(new Space()); // Do this to keep the space ID in sync with its position in the ArrayList
			 }
			 else
			 {
				 spaces.add(readSpace(reader));
			 }
		 }
		 reader.endArray();
		 name = reader.nextName();
		 reader.beginArray();
		 while (reader.hasNext()) {
			 spaceGroups.add(readSpaceGroup(reader));
			 if(!spaceGroups.get(spaceGroups.size() - 1).kind.equals("block"))
			 {
				 spaceGroups.remove(spaceGroups.size() - 1);
			 }
		 }
		 reader.endArray();
		 // reader.endObject();
		 // reader.endObject();
		 return new StaticParkingData(spaces, spaceGroups);
	}

	public SpaceGroup readSpaceGroup(JsonReader reader) throws IOException {
		String id = new String("");
		String kind = new String("");
		String title = new String("");
		ArrayList<Space> parkingSpaceIds = new ArrayList();
	
		reader.beginObject();
		while (reader.hasNext()) {
		   String name = reader.nextName();
		   if (name.equals("id")) {
			   id = reader.nextString();
		   } else if (name.equals("kind")) {
			   kind = reader.nextString();
		   } else if (name.equals("title")) {
			   title = reader.nextString();
		   } else if (name.equals("parkingSpaceIds") ) {
			   parkingSpaceIds = readParkingSpaceIDArray(reader);
		   } else {
		     reader.skipValue();
		   }
		 }
		 reader.endObject();
		 return new SpaceGroup(id, kind, parkingSpaceIds, title);
   }
	
   public ArrayList readParkingSpaceIDArray(JsonReader reader) throws IOException {
	   	 ArrayList parkingSpaceIDs = new ArrayList();
		
		 reader.beginArray();
		 while (reader.hasNext()) {
			 parkingSpaceIDs.add(reader.nextInt());
		 }
		 reader.endArray();
		 return parkingSpaceIDs;
   }
	
	public Space readSpace(JsonReader reader) throws IOException {
		int id = -1;
		int policyGroupId = -1;
		boolean isMetered = false;
		boolean isInstrumented = false;
		boolean creditCardsAccepted = false;
		boolean coinsAccepted = false;
		
		reader.beginObject();
		while (reader.hasNext()) {
		   String name = reader.nextName();
		   if (name.equals("id")) {
			   id = reader.nextInt();
		   } else if (name.equals("policyGroupId")) {
			   policyGroupId = reader.nextInt();
		   } else if (name.equals("isMetered") ) {
			   isMetered = reader.nextBoolean();
		   } else if (name.equals("isInstrumented") ) {
			   isInstrumented = reader.nextBoolean();
		   } else if (name.equals("creditCardsAccepted") ) {
			   creditCardsAccepted = reader.nextBoolean();
		   } else if (name.equals("coinsAccepted") ) {
			   coinsAccepted = reader.nextBoolean();
		   } else {
		     reader.skipValue();
		   }
		 }
		 reader.endObject();
		 return new Space(id, policyGroupId, isMetered, isInstrumented, creditCardsAccepted, coinsAccepted);
   }


	private void showErrorDialogAndQuit()
	{
		new AlertDialog.Builder(this)
		.setTitle(R.string.error_title)
		.setMessage(R.string.error_message)
		.setPositiveButton(R.string.str_ok,
			new DialogInterface.OnClickListener()
			{
				public void onClick(DialogInterface dialoginterface, int i)
				{
					finish();
				}
			})
		.show();		
	}

	/*
	 * Custom classes follow
	 */
	private class Space{
		int id;
		int policyGroupId;
		boolean isMetered;
		boolean isInstrumented;
		boolean creditCardsAccepted;
		boolean coinsAccepted;
		boolean isAvailable = false;
		
		Space(int id, int policyGroupId, boolean isMetered, boolean isInstrumented, boolean creditCardsAccepted, boolean coinsAccepted)
		{
			this.id = id;
			this.policyGroupId = policyGroupId;
			this.isMetered = isMetered;
			this.isInstrumented = isInstrumented;
			this.creditCardsAccepted = creditCardsAccepted;
			this.coinsAccepted = coinsAccepted;
		}
		
		Space()
		{
			this.id = -1;
			this.policyGroupId = -1;
		}
	}
	
	private class SpaceGroup{
		String id;
		String kind;
		ArrayList parkingSpaceIds;
		String title;
		
		SpaceGroup(String id, String kind, ArrayList spaces, String title){
			this.id = id;
			this.kind = kind;
			this.parkingSpaceIds = spaces;
			this.title = title;
		}
		
		String getTitle()
		{
			return this.title;
		}
		
		String getId()
		{
			return this.id;
		}
		
		String getKind()
		{
			return this.kind;
		}
		
		ArrayList getParkingSpaceIds()
		{
			return this.parkingSpaceIds;
		}
	}
	
	private class StaticParkingData{
		ArrayList<Space> spaces;
		ArrayList<SpaceGroup> spaceGroups;
		
		StaticParkingData(ArrayList<Space> spaces, ArrayList<SpaceGroup> spaceGroups){
			this.spaces = spaces;
			this.spaceGroups = spaceGroups;
		}
		
		StaticParkingData()
		{
			
		}
		
		ArrayList<Space> getSpaces()
		{
			return this.spaces;
		}
		
		ArrayList<SpaceGroup> getSpaceGroups()
		{
			return this.spaceGroups;
		}
	}
	
	private class ParkingArrayAdapter extends BaseAdapter {
		private ArrayList<SpaceGroup> spaceGroups;

		private LayoutInflater mInflater;

		public ParkingArrayAdapter(Context context, ArrayList<SpaceGroup> spaceGroups) {
		this.spaceGroups = spaceGroups;
		mInflater = LayoutInflater.from(context);
		}

	public int getCount() {
		return spaceGroups.size();
	}

	public Object getItem(int position) {
		return spaceGroups.get(position);
	}

	public long getItemId(int position) {
		return position;
	}

	public View getView(int position, View convertView, ViewGroup parent) {
		ViewHolder holder;
		if (convertView == null) {
			convertView = mInflater.inflate(R.layout.block_row_view, null);
			holder = new ViewHolder();
			holder.txtTitle = (TextView) convertView.findViewById(R.id.spaceTitle);
			holder.txtId = (TextView) convertView.findViewById(R.id.spaceId);

			convertView.setTag(holder);
		} else {
			holder = (ViewHolder) convertView.getTag();
		}

		holder.txtTitle.setText(spaceGroups.get(position).getTitle());
		holder.txtId.setText(spaceGroups.get(position).getId());

		return convertView;
	}

	private class ViewHolder {
		TextView txtTitle;
		TextView txtId;
	}
}


}
