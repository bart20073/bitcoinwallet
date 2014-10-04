/**
 * Copyright 2011-2012 Will Harris will@phase.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.phase.wallet;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ParseException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.ClipboardManager;
import android.text.format.DateFormat;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

class Currency implements Runnable {
	public final static int STATUS_SUCCESS = 0;
	public final static int STATUS_ERROR = 1;
	private Handler handler;
	private static long lastChecked = 0;
	private static JSONObject lastResult = null;
	private static long cacheLength = 1000 * 60 * 30; // 30 minutes
	private final static String url = "http://bitcoincharts.com/t/weighted_prices.json";

	public Currency(Handler h) {
		this.handler = h;
	}

	public static void updateWeightedCurrencies() throws IOException,
			JSONException {
		Date now = new Date();

		if ((now.getTime() - lastChecked) > cacheLength) {
			HttpClient client = new DefaultHttpClient();
			HttpGet hg = new HttpGet(url);

			HttpResponse response = client.execute(hg);

			if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
				lastResult = new JSONObject(EntityUtils.toString(response
						.getEntity()));
				lastChecked = now.getTime();
			} else {
				WalletActivity
						.toastMessage("Warning: unable to get currency information");
			}
		}
	}

	public static String[] getAvailableCurrencies() throws IOException,
			JSONException {
		String[] result = null;
		ArrayList<String> resAL = new ArrayList<String>();

		updateWeightedCurrencies();

		if (lastResult != null) {
			// JSONObject.keys() returns Iterator<String> but for some reason
			// isn't typed that way
			@SuppressWarnings("unchecked")
			Iterator<String> itr = lastResult.keys();

			// look through every transaction
			while (itr.hasNext()) {
				resAL.add(itr.next());
			}
		}

		if (resAL.size() > 0) {
			result = new String[resAL.size()];
			resAL.toArray(result);
			Arrays.sort(result);
		}

		return result;
	}

	public static double getRate(String currency) {
		double result = 0;

		if (lastResult != null) {
			try {
				// JSONObject.keys() returns Iterator<String> but for some
				// reason
				// isn't typed that way
				@SuppressWarnings("unchecked")
				Iterator<String> itr = lastResult.keys();

				// look through every transaction
				while (itr.hasNext()) {
					String cur = itr.next();
					if (cur.equals(currency)) {
						JSONObject currencyObject = lastResult
								.getJSONObject(cur);
						try {
							result = currencyObject.getDouble("30d");
							result = currencyObject.getDouble("7d");
							result = currencyObject.getDouble("24h");
						} catch (JSONException e) {
							// caught when the first failure occurs above
						}
					}
				}
			} catch (JSONException e) {

			}
		}

		return result;
	}

	@Override
	public void run() {
		try {
			updateWeightedCurrencies();
			handler.sendEmptyMessage(STATUS_SUCCESS);
		} catch (IOException e) {
			handler.sendEmptyMessage(STATUS_ERROR);
		} catch (JSONException e) {
			handler.sendEmptyMessage(STATUS_ERROR);
		}
	}
}

class Transaction implements Parcelable, Comparable<Transaction> {
	public Date date;
	public long amount;
	public long amountin;
	public long amountout;
	public String from;
	public String to;
	public long balance; // used for rolling balance

	protected static Transaction[] compressTransactions(
			Transaction[] transactions) {
		ArrayList<Transaction> txs = new ArrayList<Transaction>();
		Date currentDate = new Date();
		Transaction currentTransaction = null;

		for (Transaction transaction : transactions) {
			if (!currentDate.equals(transaction.date)) {
				currentTransaction = new Transaction(transaction.date, 0,
						transaction.from, transaction.to);
				currentTransaction.addTransaction(transaction);
				txs.add(currentTransaction);
				currentDate = transaction.date;
			} else {
				// same date, just add the data
				if (currentTransaction != null) {
					currentTransaction.addTransaction(transaction);
				}
			}
		}

		Transaction[] result = new Transaction[txs.size()];
		txs.toArray(result);

		return result;
	}

	protected static Date latest(Transaction[] transactions) {
		Date result = null;

		if (transactions != null && transactions.length > 0) {
			for (Transaction t : transactions) {
				if (result == null || result.before(t.date)) {
					result = t.date;
				}
			}
		}

		return result;
	}

	private void addTransaction(Transaction tx) {
		this.amount += tx.amount;

		if (amount >= 0) {
			this.amountin += tx.amount;
		} else {
			this.to = tx.to;
			this.amountout -= tx.amount;
		}
	}

	protected void saveTransaction(PrintWriter out) throws IOException {
		out.println(date.getTime());
		out.println(amount);
		out.println(from);
		out.println(to);
	}

	protected Transaction(BufferedReader in) throws NumberFormatException,
			IOException {
		this.date = WalletActivity.myParseDate(in.readLine());
		this.amount = Long.parseLong(in.readLine());
		this.from = in.readLine();
		this.to = in.readLine();
	}

	public Transaction(Date date, long amount, String from, String to) {
		this.date = date;
		this.amount = amount;
		this.from = from;
		this.to = to;
	}

	public Transaction(Parcel in) {
		this.date = (Date) in.readSerializable();
		this.amount = in.readLong();
		this.from = in.readString();
		this.to = in.readString();
		this.amountin = in.readLong();
		this.amountout = in.readLong();
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeSerializable(this.date);
		dest.writeLong(amount);
		dest.writeString(from);
		dest.writeString(to);
		dest.writeLong(amountin);
		dest.writeLong(amountout);
	}

	public static final Parcelable.Creator<Transaction> CREATOR = new Parcelable.Creator<Transaction>() {
		public Transaction createFromParcel(Parcel in) {
			return new Transaction(in);
		}

		public Transaction[] newArray(int size) {
			return new Transaction[size];
		}
	};

	@Override
	public int compareTo(Transaction another) {
		return another.date.compareTo(date);
	}
}

class Key {
	public String hash;
	public int hit;

	public Key(String hash) {
		this.hash = hash;
		this.hit = 0;
	}

	public Key(String hash, int hit) {
		this.hash = hash;
		this.hit = hit;
	}

	public static boolean arrayContains(Key[] keys, String hash) {
		for (Key key : keys) {
			if (key.hash.equals(hash)) {
				key.hit = 1;
				return true;
			}
		}
		return false;
	}
	
	public boolean equals( Key k )
	{
		return ( k.hash.equals( this.hash ) );
	}
}

class Wallet {
	public String name;
	public long balance;
	public Key[] keys;
	public Date lastUpdated;
	public Transaction[] transactions;
	private int version;
	private WalletActivity activity;

	private final static int LEGACY_VERSION = 1;
	private final static int TRANSACTIONS_ADDED_VERSION = 2;
	private final static int CURRENT_VERSION = 2;

	public void notifyUser() {
		/**
		 * code not ready yet if ( activity != null ) { long when =
		 * System.currentTimeMillis(); Notification n = new Notification(
		 * android.R.drawable.stat_notify_more, "Balance", when);
		 * 
		 * NotificationManager nm = (NotificationManager)
		 * activity.getSystemService( Context.NOTIFICATION_SERVICE ); Context
		 * globalContext = activity.getApplicationContext();
		 * 
		 * Intent notificationIntent = new Intent( activity, activity.getClass()
		 * ); PendingIntent contentIntent = PendingIntent.getActivity( activity,
		 * 0, notificationIntent, 0 );
		 * 
		 * n.setLatestEventInfo( globalContext, "New Balance", "New Balance",
		 * contentIntent ); nm.notify(1, n); }
		 */
	}

	protected void SaveWallet(PrintWriter out) throws IOException {
		version = CURRENT_VERSION;

		if (out != null) {
			out.println("walletversion");
			out.println(version);
			out.println(name);
			out.println(balance);
			out.println(lastUpdated.getTime());

			if (transactions == null) {
				out.println(0);
			} else {
				out.println(transactions.length);

				for (Transaction t : transactions) {
					t.saveTransaction(out);
				}
			}

			for (Key key : keys) {
				out.println(key.hash);
				out.println(key.hit);
			}
		}
	}

	private static ArrayList<Key> parseFromReader( BufferedReader bin ) throws IOException, ParseException
	{
		ArrayList<Key> keysArray = new ArrayList<Key>();
		String keyHash;
		boolean foundKey = false;

		while ((keyHash = bin.readLine()) != null) {
			// add a space at the start to make the regex work more easily
			keyHash = " " + keyHash + " ";

			Pattern p = Pattern.compile("\\W(1[1-9A-HJ-NP-Za-km-z]{27,34})\\W");
			Matcher m = p.matcher(keyHash);

			if (m.find()) {
				Log.d("balance", "Key " + m.group(1) + " found");
				Key newKey = new Key( m.group(1) );
				
				if ( !keysArray.contains( newKey ) )
				{
					keysArray.add( newKey );
					foundKey = true;
				}
			}
		}
		
		if ( foundKey )
		{
			return keysArray;
		}
		else
		{
			return null;
		}
	}

	private boolean fillFromReader(BufferedReader bin) throws IOException, ParseException
	{
		ArrayList<Key> keysArray = parseFromReader( bin );

		if (keysArray != null )
		{
			keys = new Key[keysArray.size()];
			keysArray.toArray(keys);
			lastUpdated = new Date();
			balance = 0;
		}

		return ( keysArray != null );
	}

	public int addFromReader(BufferedReader bin) throws IOException, ParseException
	{
		ArrayList<Key> keysArray = parseFromReader( bin );
		int added = 0;

		if ( keys != null && keysArray != null )
		{
			ArrayList<Key> newKeys = new ArrayList<Key>();
			
			// add all existing keys
			for ( int i = 0; i < keys.length; i++ )
			{
				newKeys.add( keys[i] );
			}

			// add new keys
			for ( int i = 0; i < keysArray.size(); i++ )
			{
				Key newKey = keysArray.get( i );

				// only if not already in there
				if ( !Key.arrayContains( keys, newKey.hash ) )
				{
					newKey.hit = 1;
					newKeys.add( newKey );
					added++;
				}
			}

			// recreate array
			keys = new Key[newKeys.size()];
			newKeys.toArray(keys);
		}

		return added;
	}

	public Wallet(String name, File file, WalletActivity activity)
			throws IOException {
		this.activity = activity;
		this.name = name;
		boolean foundKey = false;
		BufferedReader in = new BufferedReader(new FileReader(file));

		foundKey = fillFromReader(in);

		in.close();

		if (!foundKey) {
			throw new ParseException("Did not find any keys");
		}
	}

	public Wallet(String name, URI url, WalletActivity activity)
			throws IOException, ParseException {
		this.activity = activity;
		this.name = name;
		HttpClient client = new DefaultHttpClient();
		HttpGet hg = new HttpGet(url);
		boolean foundKey = false;

		HttpResponse resp;
		resp = client.execute(hg);
		if (resp.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
			HttpEntity entity = resp.getEntity();

			if (entity != null) {
				foundKey = fillFromReader(new BufferedReader(
						new InputStreamReader(entity.getContent()), (int) entity.getContentLength() ));
			} else {
				throw new ParseException("No response body");
			}
		} else {
			throw new ParseException("Did not get 200 OK");
		}

		if (!foundKey) {
			throw new ParseException("Did not find any keys");
		}
	}

	protected Wallet(BufferedReader in, WalletActivity activity)
			throws IOException, NumberFormatException {
		this.activity = activity;
		name = in.readLine();
		if (name.equals("walletversion")) {
			version = Integer.parseInt(in.readLine());
			name = in.readLine();
		} else {
			version = LEGACY_VERSION;
		}
		balance = Long.parseLong(in.readLine());
		lastUpdated = WalletActivity.myParseDate(in.readLine());

		if (version >= TRANSACTIONS_ADDED_VERSION) {
			int numTx = Integer.parseInt(in.readLine());
			transactions = new Transaction[numTx];
			for (int i = 0; i < numTx; i++) {
				transactions[i] = new Transaction(in);
			}
		}

		ArrayList<Key> keysArray = new ArrayList<Key>();
		String keyHash = null;

		while ((keyHash = in.readLine()) != null) {
			int hit = Integer.parseInt(in.readLine());
			keysArray.add(new Key(keyHash, hit));
		}
		;

		keys = new Key[keysArray.size()];
		keysArray.toArray(keys);
	}

	public Wallet(String name, String inputkeys, WalletActivity parentActivity)
			throws IOException, ParseException {
		this.activity = parentActivity;
		this.name = name;
		boolean foundKey = false;
		ArrayList<Key> keysArray = new ArrayList<Key>();
		Pattern p = Pattern.compile("\\W(1[1-9A-HJ-NP-Za-km-z]{27,34})");

		for (String keyHash : inputkeys.split(" ")) {
			// add a space at the start to make the regex work more easily
			keyHash = " " + keyHash;

			Matcher m = p.matcher(keyHash);

			if (m.find()) {
				Log.d("balance", "Key " + m.group(1) + " found");
				keysArray.add(new Key(m.group(1)));
				foundKey = true;
			}
		}

		if (foundKey) {
			keys = new Key[keysArray.size()];
			keysArray.toArray(keys);
			lastUpdated = new Date();
			balance = 0;
		} else {
			throw new ParseException("Did not find any keys");
		}
	}

	public static Wallet[] getStoredWallets(Context context,
			WalletActivity activity) throws IOException {
		ArrayList<Wallet> walletsArray = new ArrayList<Wallet>();

		String[] files = context.fileList();

		if (files.length == 0)
			return null;

		for (String filename : files) {
			BufferedReader in = new BufferedReader(new InputStreamReader(
					context.openFileInput(filename)));
			try {
				walletsArray.add(new Wallet(in, activity));
			} catch (NumberFormatException e) {
			}
			in.close();
		}
		Wallet[] wallets = new Wallet[walletsArray.size()];
		walletsArray.toArray(wallets);
		return wallets;
	}

	public static void saveWallets(Wallet[] wallets, Context context)
			throws IOException {
		for (Wallet w : wallets) {
			PrintWriter out = new PrintWriter(new OutputStreamWriter(
					context.openFileOutput(w.name, Context.MODE_PRIVATE)));
			w.SaveWallet(out);
			out.close();
		}
	}

	public int getActiveKeyCount() {
		int count = 0;

		for (Key k : keys) {
			if (k.hit > 0)
				count++;
		}

		if (count == 0)
			count = keys.length;

		return count;
	}
}

class WalletAdapter extends BaseAdapter {
	private Wallet[] wallets;
	private WalletActivity context;
	private int decimalpoints = 2;

	public WalletAdapter(WalletActivity c, Wallet[] w, int decimalpoints) {
		this.wallets = w;
		this.context = c;
		this.decimalpoints = decimalpoints;
	}

	@Override
	public int getCount() {
		return wallets.length;
	}

	@Override
	public Object getItem(int position) {
		return wallets[position];
	}

	@Override
	public long getItemId(int position) {
		return 0;
	}

	private String getTimeStampString(Date d) {
		String result = DateFormat.getDateFormat(context).format(d);

		result += " " + DateFormat.format("h:mmaa", d).toString();

		return result;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		double exchrate = Currency.getRate(context.getActiveCurrency());

		LayoutInflater inflater = LayoutInflater.from(context);

		View v = inflater.inflate(R.layout.walletlayout, null);
		v.setLongClickable(true);
		v.setOnClickListener(context);
		v.setTag(position);

		DecimalFormat df = new DecimalFormat(
				WalletActivity.decimalString(decimalpoints));

		TextView balanceTextView = (TextView) v
				.findViewById(R.id.walletBalanceText);
		balanceTextView.setText(df.format(wallets[position].balance
				/ BalanceRetriever.SATOSHIS_PER_BITCOIN));

		TextView curTextView = (TextView) v.findViewById(R.id.walletCurText);
		curTextView.setTextSize(10);

		if (exchrate != 0) {
			curTextView.setText("("
					+ df.format(wallets[position].balance * exchrate
							/ BalanceRetriever.SATOSHIS_PER_BITCOIN)
					+ context.getActiveCurrency() + ")");
		} else {
			curTextView.setText("");
		}

		balanceTextView.setTextSize(20);
		balanceTextView.setTextColor(Color.GREEN);

		TextView nameTextView = (TextView) v.findViewById(R.id.walletNameText);
		nameTextView.setText(wallets[position].name);
		nameTextView.setTextColor(Color.BLACK);
		nameTextView.setTextSize(16);

		TextView lastUpdatedTextView = (TextView) v
				.findViewById(R.id.lastUpdatedText);

		lastUpdatedTextView.setTextColor(Color.GRAY);
		lastUpdatedTextView.setTextSize(8);
		lastUpdatedTextView.setText("Last Updated: "
				+ getTimeStampString(wallets[position].lastUpdated));

		TextView infoTextView = (TextView) v.findViewById(R.id.infoText);

		infoTextView.setTextColor(Color.GRAY);
		infoTextView.setTextSize(8);
		infoTextView.setText(wallets[position].keys.length + " keys ("
				+ wallets[position].getActiveKeyCount() + " in use)");

		TextView txLastUpdatedTextView = (TextView) v
				.findViewById(R.id.txLastUpdatedText);
		txLastUpdatedTextView.setTextColor(Color.GRAY);
		txLastUpdatedTextView.setTextSize(8);

		TextView txInfoTextView = (TextView) v.findViewById(R.id.txInfoText);
		txInfoTextView.setTextColor(Color.GRAY);
		txInfoTextView.setTextSize(8);

		if (wallets[position].transactions != null
				&& wallets[position].transactions.length > 0) {
			txLastUpdatedTextView.setText("Last Transaction: "
					+ getTimeStampString(Transaction
							.latest(wallets[position].transactions)));
			txInfoTextView
					.setText(wallets[position].transactions.length
							+ " transactions ("
							+ Transaction
									.compressTransactions(wallets[position].transactions).length
							+ " unique)");
		} else {
			txLastUpdatedTextView.setText("");
			txInfoTextView.setText("");
		}

		Button button = (Button) v.findViewById(R.id.updateButton);
		button.setTag(position);
		button.setOnClickListener(context);
		return v;
	}
}

public class WalletActivity extends Activity implements OnClickListener {
	public final static String TRANSACTIONS = "net.phase.wallet.transactions";
	public final static String WALLETNAME = "net.phase.wallet.name";
	public final static String TRANSACTIONSTYLE = "net.phase.wallet.transactionsstyle";
	public final static String DECIMALPOINTS = "net.phase.wallet.decimal";
	private ProgressDialog dialog;
	private Wallet[] wallets;
	private int nextWallet = -1;
	private static final int DIALOG_URL = 1;
	private static final int DIALOG_FILE = 2;
	private static final int DIALOG_OPTIONS = 3;
	private static final int DIALOG_PASTE = 4;
	private String activeCurrency = "USD";
	private static Context context;
	private int maxlength = 40;
	private int decimalpoints = 2;
	private static ProgressDialog progress = null;

	public static void dismissProgress()
	{
		if ( progress != null )
		{
			progress.dismiss();
			progress = null;
		}
	}
	public static String decimalString(int decimalpoints) {
		if (decimalpoints == 0)
			return "0";

		StringBuilder result = new StringBuilder("0.");

		for (int i = 0; i < decimalpoints; i++) {
			result.append('0');
		}

		return result.toString();
	}

	public static Date myParseDate(String line) {
		Date d = null;

		// make guesses - if it contains a space, it's probably the output of
		// date.toString()
		if (line.indexOf(' ') == -1) {
			try {
				d = new Date(Long.parseLong(line));
			} catch (NumberFormatException e) {

			}
		} else {
			try {
				d = new Date(line);
			} catch (IllegalArgumentException e) {
			}
		}

		if (d == null) {
			// set it to 1970... not much we can do here
			d = new Date(0);
		}

		return d;
	}

	private boolean savePreferences() {
		SharedPreferences pref = getPreferences(MODE_PRIVATE);

		SharedPreferences.Editor editor = pref.edit();
		editor.putString("currency", activeCurrency);
		editor.putInt("maxlength", maxlength);
		editor.putInt("decimal", decimalpoints);
		return editor.commit();
	}

	private void loadPreferences() {
		SharedPreferences pref = getPreferences(MODE_PRIVATE);
		setActiveCurrency(pref.getString("currency", "USD"));
		maxlength = pref.getInt("maxlength", 40);
		decimalpoints = pref.getInt("decimal", 2);
	}

	public static void toastMessage(String message) {
		CharSequence text = message;
		int duration = Toast.LENGTH_SHORT;

		if (context != null) {
			Toast toast = Toast.makeText(context, text, duration);
			toast.show();
		}
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		WalletActivity.context = getApplicationContext();
		loadPreferences();
		try {
			wallets = Wallet.getStoredWallets(this, this);
		} catch (IOException e) {
			toastMessage(e.getMessage());
		}

		Currency c = new Currency(currencyHandler);
		Thread t = new Thread(c);
		t.start();

		updateWalletList();
	}

	public void addWallet(Wallet wallet) {
		if (wallets == null) {
			wallets = new Wallet[1];
			wallets[0] = wallet;
		} else {
			Wallet[] newWallets = new Wallet[wallets.length + 1];
			int i = 0;
			for (i = 0; i < wallets.length; i++) {
				newWallets[i] = wallets[i];
				if (newWallets[i].name.equals(wallet.name)) {
					// name clash!
					wallet.name = wallet.name.concat("2");
				}
			}
			newWallets[i] = wallet;
			wallets = newWallets;
		}
		updateWalletList();
	}

	public void updateWalletList() {
		if (wallets != null) {
			setContentView(R.layout.main);
			ListView view = (ListView) findViewById(R.id.walletListView);

			WalletAdapter adapter = new WalletAdapter(this, wallets,
					decimalpoints);
			view.setAdapter(adapter);

			double exchrate = Currency.getRate(getActiveCurrency());
			long balance = 0;

			for (int i = 0; i < wallets.length; i++) {
				balance += wallets[i].balance;
			}
			DecimalFormat df = new DecimalFormat(decimalString(decimalpoints));

			TextView btcBalance = (TextView) findViewById(R.id.btcBalance);
			btcBalance.setText(df.format(balance
					/ BalanceRetriever.SATOSHIS_PER_BITCOIN)
					+ " BTC");

			TextView curBalance = (TextView) findViewById(R.id.curBalance);
			if (exchrate != 0) {
				curBalance.setText(df.format(balance * exchrate
						/ BalanceRetriever.SATOSHIS_PER_BITCOIN)
						+ " " + getActiveCurrency());
			} else {
				curBalance.setText("");
			}

			registerForContextMenu(view);
			try {
				Wallet.saveWallets(wallets, this);
			} catch (IOException e) {
				toastMessage("Unable to save wallet data " + e.getMessage());
			}
		} else {
			setContentView(R.layout.mainnowallets);
		}
	}

	private void updateAll() {
		if (wallets != null) {
			if (wallets.length > 0) {
				nextWallet = 1;
			}

			updateWalletBalance(wallets[0], true, maxlength);
		} else {
			toastMessage("No wallets to update!");
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.mainmenu, menu);
		return true;
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);

		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.walletmenu, menu);
	}

	FileFilter fileFilter = new FileFilter() {
		@Override
		public boolean accept(File pathname) {
			if (pathname.getName().contains("key")
					&& pathname.getName().endsWith(".txt")) {
				return true;
			}
			return false;
		}
	};

	protected void onPrepareDialog(int id, Dialog dialog) {
		switch (id) {
		case DIALOG_URL:
			TextView tv = (TextView) dialog.findViewById(R.id.pasteBinHelpText);
			tv.setMovementMethod(LinkMovementMethod.getInstance());

			break;
		case DIALOG_FILE:
			TextView tvhelp = (TextView) dialog.findViewById(R.id.helpText);
			tvhelp.setMovementMethod(LinkMovementMethod.getInstance());

			Spinner fileSpinner = (Spinner) dialog.findViewById(R.id.fileInput);

			File[] files = Environment.getExternalStorageDirectory().listFiles(
					fileFilter);

			if (files == null || files.length == 0) {
				toastMessage("No files found on sdcard");
			} else {
				ArrayAdapter<File> adapter = new ArrayAdapter<File>(this,
						android.R.layout.simple_spinner_item, files);
				fileSpinner.setAdapter(adapter);
			}
			break;
		case DIALOG_PASTE:
			EditText keyText = (EditText) dialog.findViewById(R.id.keysText);

			keyText.setText("");
			break;
		case DIALOG_OPTIONS:
			EditText reqText = (EditText) dialog.findViewById(R.id.reqText);

			reqText.setText(Integer.toString(maxlength));
			Spinner currencySpinner = (Spinner) dialog
					.findViewById(R.id.currencySpinner);
			Spinner decimalSpinner = (Spinner) dialog
					.findViewById(R.id.decpointSpinner);

			String current = getActiveCurrency();
			String[] currencies = { current };

			try {
				currencies = Currency.getAvailableCurrencies();
			} catch (IOException e) {
				WalletActivity
						.toastMessage("Warning: unable to obtain currency information");
			} catch (JSONException e) {
			}
			ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
					android.R.layout.simple_spinner_item, currencies);
			currencySpinner.setAdapter(adapter);
			for (int i = 0; i < currencies.length; i++) {
				if (currencies[i].equals(current)) {
					currencySpinner.setSelection(i);
					break;
				}
			}

			Integer[] decimaloptions = { 0, 1, 2, 3, 4, 5 };

			ArrayAdapter<Integer> adapter2 = new ArrayAdapter<Integer>(this,
					android.R.layout.simple_spinner_item, decimaloptions);
			decimalSpinner.setAdapter(adapter2);
			for (int i = 0; i < decimaloptions.length; i++) {
				if (decimaloptions[i].intValue() == decimalpoints) {
					decimalSpinner.setSelection(i);
					break;
				}
			}

			break;
		}
	}

	private void showTransactions(Wallet wallet) {
		showTransactions(wallet, TransactionAdapter.STYLE_NORMAL);
	}

	private void showTransactions(Wallet wallet, int style)
	{
		if ( progress == null && wallet.transactions.length > 10 )
		{
			progress = ProgressDialog.show( this, "Building Transaction List", 
	                "Please wait...", true);
		}

		Intent intent = new Intent(this, TransactionsActivity.class);
		intent.putExtra(WALLETNAME, wallet.name);
		intent.putExtra(TRANSACTIONSTYLE, style);
		intent.putExtra(DECIMALPOINTS, decimalpoints);
		Arrays.sort(wallet.transactions);

		if (style == TransactionAdapter.STYLE_NORMAL) {
			Transaction[] compressedTransactions = Transaction
					.compressTransactions(wallet.transactions);
			intent.putExtra(TRANSACTIONS, compressedTransactions);
		} else {
			intent.putExtra(TRANSACTIONS, wallet.transactions);
		}

		startActivity(intent);
	}

	protected Dialog onCreateDialog(int id) {
		AlertDialog.Builder builder;
		AlertDialog alertDialog;
		builder = new AlertDialog.Builder(this);
		LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
		View layout = null;
		final WalletActivity parentActivity = this;

		switch (id) {
		case DIALOG_URL:
			layout = inflater.inflate(R.layout.urlfetch_dialog, null);

			TextView tv = (TextView) layout.findViewById(R.id.pasteBinHelpText);
			tv.setMovementMethod(LinkMovementMethod.getInstance());

			final EditText hashEditText = (EditText) layout
					.findViewById(R.id.hashEditText);
			final EditText nameEditText = (EditText) layout
					.findViewById(R.id.nameEditText);

			builder.setView(layout);
			builder.setPositiveButton("Ok",
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							String hash = hashEditText.getText().toString();
							String name = nameEditText.getText().toString();

							if (!hash.startsWith("http")) {
								hash = "http://pastebin.com/raw.php?i=" + hash;
							}

							try {
								Wallet w = new Wallet(name, new URI(hash),
										parentActivity);
								addWallet(w);
							} catch (Exception e) {
								toastMessage(e.getMessage());
							}
						}
					});
			builder.setNegativeButton("Cancel",
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							dialog.cancel();
						}
					});
			break;
		case DIALOG_FILE:
			layout = inflater.inflate(R.layout.file_dialog, null);

			TextView tvhelp = (TextView) layout.findViewById(R.id.helpText);
			tvhelp.setMovementMethod(LinkMovementMethod.getInstance());

			final Spinner fileSpinner = (Spinner) layout
					.findViewById(R.id.fileInput);
			final EditText nameEditText2 = (EditText) layout
					.findViewById(R.id.nameEditText);

			builder.setView(layout);
			builder.setPositiveButton("Ok",
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							File filename = (File) fileSpinner
									.getSelectedItem();
							String name = nameEditText2.getText().toString();
							boolean alreadyexists = false;

							if ( name.length() > 0)
							{
								if ( wallets != null && wallets.length > 0 )
								{
									for (Wallet w : wallets )
									{
										if ( w.name.equals (name ) )
										{
											alreadyexists = true;
										}
									}
								}

								if ( !alreadyexists )
								{
									try {
										Wallet w = new Wallet(name, filename,
												parentActivity);
										addWallet(w);
									} catch (Exception e) {
										toastMessage(e.getMessage());
									}
								}
								else
								{
									toastMessage("Wallet already exists");
								}
							}
							else
							{
								toastMessage( "Invalid Name" );
							}
						}
					});

			builder.setNegativeButton("Cancel",
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							dialog.dismiss();
						}
					});
			break;
		case DIALOG_PASTE:
			layout = inflater.inflate(R.layout.paste_dialog, null);

			final EditText keysText = (EditText) layout
					.findViewById(R.id.keysText);
			final EditText nameEditText3 = (EditText) layout
					.findViewById(R.id.walletNameText);

			builder.setView(layout);
			builder.setPositiveButton("Ok",
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							String keys = keysText.getText().toString();
							String name = nameEditText3.getText().toString();
							boolean alreadyexists = false;

							if ( name.length() > 0)
							{
								if ( wallets != null && wallets.length > 0 )
								{
									for (Wallet w : wallets )
									{
										if ( w.name.equals (name ) )
										{
											alreadyexists = true;
										}
									}
								}

								if ( !alreadyexists )
								{
									try {
										Wallet w = new Wallet(name, keys,
												parentActivity);
										addWallet(w);
									} catch (Exception e) {
										toastMessage(e.getMessage());
									}
								}
								else
								{
									toastMessage("Wallet already exists");
								}
							}
							else
							{
								toastMessage( "Invalid Name" );
							}
						}
					});

			builder.setNegativeButton("Cancel",
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							dialog.dismiss();
						}
					});
			break;
		case DIALOG_OPTIONS:
			layout = inflater.inflate(R.layout.options_dialog, null);

			final Spinner currencySpinner = (Spinner) layout
					.findViewById(R.id.currencySpinner);
			final EditText reqText = (EditText) layout
					.findViewById(R.id.reqText);
			final Spinner decpointSpinner = (Spinner) layout
					.findViewById(R.id.decpointSpinner);

			builder.setView(layout);
			builder.setPositiveButton("Ok",
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							String currency = (String) currencySpinner
									.getSelectedItem();
							decimalpoints = ((Integer) decpointSpinner
									.getSelectedItem()).intValue();
							setActiveCurrency(currency);
							try {
								maxlength = Integer.parseInt(reqText.getText()
										.toString());
							} catch (RuntimeException e) {
								toastMessage("Invalid number");
							}
							savePreferences();
						}
					});
			builder.setNegativeButton("Cancel",
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							dialog.cancel();
						}
					});
			break;

		}

		alertDialog = builder.create();

		return alertDialog;
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item
				.getMenuInfo();

		switch (item.getItemId()) {
		case R.id.updateItem:
			updateWalletBalance(wallets[info.position], false, maxlength);
			return true;
		case R.id.removeItem:
			removeWallet(wallets[info.position].name);
			updateWalletList();
			return true;
		case R.id.pasteClipKeys:
			ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			if ( clip != null )
			{
				if ( clip.getText() != null )
				{
					int added = 0;

					try
					{
						InputStream is = new ByteArrayInputStream(clip.getText().toString().getBytes());
	
						BufferedReader br = new BufferedReader( new InputStreamReader( is ) );
	
						added = wallets[info.position].addFromReader( br );
					}
					catch (IOException e)
					{
					}

					if ( added > 0 )
					{
						toastMessage( "Added " + added + " key(s)");
						updateWalletList();
					}
					else
					{
						toastMessage( "Found no new keys ");
					}
				}
				else
				{
					toastMessage( "Nothing on clipboard" );
				}
			}
			else
			{
				toastMessage("Could not obtain clipboard");
			}
			return true;
		case R.id.viewItem:
			if (wallets[info.position].transactions == null) {
				toastMessage("Please update wallet first");
			} else {
				showTransactions(wallets[info.position]);
			}
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.importURL:
			showDialog(DIALOG_URL);
			return true;
		case R.id.importFile:
			showDialog(DIALOG_FILE);
			return true;
		case R.id.importPaste:
			showDialog(DIALOG_PASTE);
			return true;
		case R.id.updateAllItem:
			updateAll();
			return true;
		case R.id.optionsItem:
			showDialog(DIALOG_OPTIONS);
			return true;
		case R.id.helpItem:
			Uri uri = Uri
					.parse("http://code.google.com/p/bitcoinwallet/wiki/Using");
			startActivity(new Intent(Intent.ACTION_VIEW, uri));
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	public Thread updateWalletBalance(Wallet w, boolean fast, int maxlength) {
		BalanceRetriever br = new BalanceRetriever(progressHandler, w, fast,
				maxlength);

		dialog = new ProgressDialog(this);
		dialog.setMessage("Obtaining balance (" + w.name + ")...");
		dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		if (fast)
			dialog.setMax(w.getActiveKeyCount());
		else
			dialog.setMax(w.keys.length);
		dialog.setProgress(0);
		dialog.show();

		Thread t = new Thread(br);
		t.start();

		return t;
	}

	public void removeWallet(String name) {
		Wallet[] newWallets = null;

		if (wallets.length == 1) {
			wallets = null;
			updateWalletList();
		} else {
			newWallets = new Wallet[wallets.length - 1];
			int i = 0;
			for (Wallet wallet : wallets) {
				if (!wallet.name.equals(name))
					newWallets[i++] = wallet;
			}
		}
		// delete the wallet file
		deleteFile(name);
		wallets = newWallets;
	}

	Handler currencyHandler = new Handler() {
		public void handleMessage(Message msg) {
			updateWalletList();
		}
	};

	Handler progressHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case BalanceRetriever.MESSAGE_UPDATE:
				dialog.incrementProgressBy(1);
				break;
			case BalanceRetriever.MESSAGE_FINISHED:
				dialog.dismiss();
				switch (msg.arg1) {
				case BalanceRetriever.MESSAGE_STATUS_SUCCESS:
					// code to refresh all wallets
					if (nextWallet > 0 && nextWallet < wallets.length) {
						updateWalletBalance(wallets[nextWallet], false,
								maxlength);
						nextWallet++;
					} else {
						updateWalletList();

						// send an update to the widget
						Intent intent = new Intent(
								WalletWidgetProvider.UPDATE_WIDGET);
						intent.setClassName("net.phase.wallet",
								"net.phase.wallet.WalletWidgetProvider");
						sendBroadcast(intent);

						nextWallet = -1;
					}
					break;
				case BalanceRetriever.MESSAGE_STATUS_NETWORK:
					toastMessage("Network error");
					break;
				case BalanceRetriever.MESSAGE_STATUS_NOKEYS:
					toastMessage("No keys at that location");
					break;
				case BalanceRetriever.MESSAGE_STATUS_JSON:
					toastMessage("JSON Parse Error");
					break;
				case BalanceRetriever.MESSAGE_STATUS_PARSE:
					toastMessage("Parse Error");
					break;
				}
				break;
			case BalanceRetriever.MESSAGE_SETLENGTH:
				dialog.setMax(msg.arg1);
				break;
			}
		}
	};

	@Override
	public void onClick(View v) {
		int i = (Integer) v.getTag();

		if (v.getId() == R.id.updateButton) {
			updateWalletBalance(wallets[i], true, maxlength);
		} else {
			if (wallets[i].transactions == null) {
				toastMessage("Please update wallet");
			} else {
				showTransactions(wallets[i]);
			}
		}
	}

	public String getActiveCurrency() {
		return activeCurrency;
	}

	public void setActiveCurrency(String currency) {
		activeCurrency = currency;
		updateWalletList();
	}
}

class tx {
	public String txhash;
	public int rec;
	public long value;
	public String inKeyHash;
	public String outKeyHash;

	public tx(String txhash, int rec, long value, String outKeyHash,
			String inKeyHash) {
		// value is number of satoshis
		this.txhash = txhash;
		this.rec = rec;
		this.value = value;
		this.outKeyHash = outKeyHash;
		this.inKeyHash = inKeyHash;
	}
}

class prevout {
	public String prevTxHash;
	public String txhash;
	public int rec;
	public Date date;
	public String addr;

	public prevout(String txhash, String prevTxHash, int rec, Date date,
			String addr) {
		this.txhash = txhash;
		this.prevTxHash = prevTxHash;
		this.rec = rec;
		this.date = date;
		this.addr = addr;
	}
}

class BalanceRetriever implements Runnable {
	public static final int MESSAGE_UPDATE = 1;
	public static final int MESSAGE_FINISHED = 2;
	public static final int MESSAGE_SETLENGTH = 3;

	public static final int MESSAGE_STATUS_SUCCESS = 0;
	public static final int MESSAGE_STATUS_NOKEYS = 1;
	public static final int MESSAGE_STATUS_NETWORK = 2;
	public static final int MESSAGE_STATUS_JSON = 3;
	public static final int MESSAGE_STATUS_PARSE = 4;
	public static final int MESSAGE_STATUS_MISSING_TX = 5;

	public static final double SATOSHIS_PER_BITCOIN = 100000000.0;
	private static final String baseUrl = "http://blockexplorer.com/q/mytransactions/";
	private static final String emptyBaseUrl = "http://blockexplorer.com/q/mytransactions";

	// number of transactions that can be queried from blockexplorer in each GET
	// request
	// this is limited by the maximum length of a GET request
	private int maxlength = 40;
	// balance is number of microbitcoins (a millionth of a bitcoin)
	private long balance;
	private Handler updateHandler;
	private Wallet wallet;
	private boolean fast;

	private ArrayList<String> transactionCache;
	private ArrayList<Transaction> transactions;
	private ArrayList<tx> txs;
	private ArrayList<prevout> pendingDebits;
	private static final java.text.DateFormat formatter = new SimpleDateFormat(
			"yyyy-MM-dd HH:mm:ss");

	public long getFinalBalance() {
		return this.balance;
	}

	public int getNumberOfKeys() {
		return wallet.keys.length;
	}

	public BalanceRetriever(Handler updateHandler, Wallet wallet, boolean fast,
			int maxlength) {
		this.balance = 0;
		this.updateHandler = updateHandler;
		this.wallet = wallet;
		this.fast = fast;
		this.maxlength = maxlength;

		transactions = new ArrayList<Transaction>();
		transactionCache = new ArrayList<String>();
		txs = new ArrayList<tx>();
		pendingDebits = new ArrayList<prevout>();
	}

	private tx getMatchingTx(String txhash, int rec) {
		for (tx theTx : txs) {
			if (theTx.txhash.equals(txhash) && theTx.rec == rec) {
				return theTx;
			}
		}
		// Log.e("balance", "Could not find hash " + hash + ":" + rec );
		return null;
	}

	private tx getFirstNonMatchingTx(String txhash, Key[] keys) {
		for (tx theTx : txs) {
			if (theTx.txhash.equals(txhash)
					&& !Key.arrayContains(keys, theTx.outKeyHash)) {
				return theTx;
			}
		}
		return null;
	}

	public void run() {
		balance = 0;
		int i = 0;
		StringBuffer url = new StringBuffer(baseUrl);
		int status = MESSAGE_STATUS_SUCCESS;
		boolean fastKeyFound = false;

		for (Key k : wallet.keys) {
			if (!fast) {
				k.hit = 0;
			} else {
				if (k.hit > 0) {
					fastKeyFound = true;
				}
			}
		}

		// if we asked for fast, but no fast keys found, just override this
		if (fast && !fastKeyFound) {
			fast = false;
		}

		try {
			int numberOfKeys = wallet.keys.length;

			if (fast) {
				numberOfKeys = wallet.getActiveKeyCount();
			}

			updateHandler.sendMessage(updateHandler.obtainMessage(
					MESSAGE_SETLENGTH, numberOfKeys, 0));
			updateHandler.sendMessage(updateHandler
					.obtainMessage(MESSAGE_UPDATE));

			for (Key key : wallet.keys) {
				if ((i % maxlength) == (maxlength - 1)) {
					updateBalanceFromUrl(url.substring(0, url.length() - 1));

					url = new StringBuffer(baseUrl);
				}

				if (!fast || key.hit > 0) {
					url.append(key.hash);
					url.append('.');
					i++;
				}
				updateHandler.sendMessage(updateHandler
						.obtainMessage(MESSAGE_UPDATE));
			}

			updateBalanceFromUrl(url.substring(0, url.length() - 1));

			updateHandler.sendMessage(updateHandler
					.obtainMessage(MESSAGE_UPDATE));

			// look through previous transactions and debit payments
			for (prevout previousOut : pendingDebits) {
				tx matchingTx = getMatchingTx(previousOut.prevTxHash,
						previousOut.rec);

				if (matchingTx != null) {
					balance -= matchingTx.value;

					tx outputTx = getFirstNonMatchingTx(previousOut.txhash,
							wallet.keys);
					if (outputTx != null) {
						transactions.add(new Transaction(previousOut.date,
								-matchingTx.value, previousOut.addr,
								outputTx.outKeyHash));
					} else {
						transactions
								.add(new Transaction(previousOut.date,
										-matchingTx.value, previousOut.addr,
										"unknown"));
					}
				} else {
					status = MESSAGE_STATUS_MISSING_TX;
					// not sure how this can happen, but it happened once for a
					// user, so handle it here
					Log.w("wallet", "could not retrieve previous tx for "
							+ previousOut.prevTxHash + ":" + previousOut.rec);
				}
			}
		} catch (IOException e) {
			status = MESSAGE_STATUS_NETWORK;
		} catch (JSONException e) {
			status = MESSAGE_STATUS_JSON;
		} catch (java.text.ParseException e) {
			status = MESSAGE_STATUS_PARSE;
		}
		
		updateHandler.sendMessage(updateHandler.obtainMessage(MESSAGE_FINISHED,
				status, 0));

		if (status == MESSAGE_STATUS_SUCCESS) {
			if (wallet.balance != 0 && wallet.balance != balance) {
				wallet.notifyUser();
			}
			wallet.balance = balance;
			wallet.lastUpdated = new Date();
			wallet.transactions = new Transaction[transactions.size()];
			transactions.toArray(wallet.transactions);
		}
	}

	private void updateBalanceFromUrl(String url) throws IOException,
			JSONException, java.text.ParseException
	{
		if ( url.equals( emptyBaseUrl ) )
		{
			Log.i("balance", "skipping URL " + url );
			return;
		}

		Log.i("balance", "fetching URL " + url);
		HttpClient client = new DefaultHttpClient();
		HttpGet hg = new HttpGet(url);

		HttpResponse response = client.execute(hg);

		if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK)
		{
			JSONObject resp = new JSONObject(EntityUtils.toString(response.getEntity()));				

			// JSONObject.keys() returns Iterator<String> but for some reason
			// isn't typed that way
			@SuppressWarnings("unchecked")
			Iterator<String> itr = resp.keys();

			// look through every transaction
			while (itr.hasNext()) {
				JSONObject txObject = resp.getJSONObject(itr.next());
				String txHash = txObject.getString("hash");
				String inKeyHash = "unknown";

				// only process transaction if we haven't seen it before
				if (!transactionCache.contains(txHash)) {
					Log.i("balance", "Parsing txObject " + txHash);
					transactionCache.add(txHash);
					// find the in transaction
					JSONArray txsIn = txObject.getJSONArray("in");
					Date date = formatter.parse(txObject.getString("time"));

					for (int i = 0; i < txsIn.length(); i++) {
						JSONObject inRecord = txsIn.getJSONObject(i);
						try {
							inKeyHash = inRecord.optString("address");

							// if one of our keys is there, we are paying :(
							if (Key.arrayContains(wallet.keys, inKeyHash)) {
								JSONObject prevRecord = inRecord
										.getJSONObject("prev_out");
								// if we paid for part of this transaction,
								// record this.
								pendingDebits
										.add(new prevout(txHash, prevRecord
												.getString("hash"), prevRecord
												.getInt("n"), date, inKeyHash));
							}
						} catch (JSONException e) {
							// no address. Probably a generation transaction
						}
					}

					// find the out transaction
					JSONArray txsOut = txObject.getJSONArray("out");

					for (int i = 0; i < txsOut.length(); i++) {
						JSONObject outRecord = txsOut.getJSONObject(i);
						String outKeyHash = outRecord.optString("address");
						// convert to microbitcoins for accuracy
						long value = (long) (outRecord.getDouble("value") * SATOSHIS_PER_BITCOIN);
						// store the out transaction, this is used later on
						txs.add(new tx(txHash, i, value, outKeyHash, inKeyHash));

						// if one of our keys is there, add the balance
						if (Key.arrayContains(wallet.keys, outKeyHash)) {
							transactions.add(new Transaction(date, value,
									inKeyHash, outKeyHash));
							balance += value;
						}
					}
				}
			}
		} else {
			Log.e("wallet", "Got " + response.getStatusLine().getStatusCode()
					+ " back from HTTP GET");
		}
	}
}
