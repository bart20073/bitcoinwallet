/**
 * Copyright 2011 Will Harris will@phase.net
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

import java.text.DecimalFormat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

class TransactionAdapter extends BaseAdapter
{
	public final static int STYLE_UNKNOWN = 0;
	public final static int STYLE_NORMAL = 1;
	public final static int STYLE_SIDEBYSIDE = 2;
	public final static int STYLE_DETAILED = 3;
	
	private Transaction [] transactions;
	private TransactionsActivity context;
	private int layoutStyle;
	private int decimalpoints = 2;
	
	public TransactionAdapter( TransactionsActivity c, Transaction [] transactions, int layoutStyle, int decimalpoints )
	{
		this.transactions = transactions;
		this.layoutStyle = layoutStyle;
		this.context = c;
		this.decimalpoints = decimalpoints;
	}

	@Override
	public int getCount()
	{
		return transactions.length;
	}

	@Override
	public Object getItem(int position)
	{
		return transactions[position];
	}

	@Override
	public long getItemId(int position)
	{
		return 0;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent)
	{
		LayoutInflater inflater = LayoutInflater.from( context );
		
		View v = inflater.inflate( R.layout.transactionlayout, null );
		
		TextView txInText = (TextView) v.findViewById(R.id.txInText);
		TextView txOutText = (TextView) v.findViewById(R.id.txOutText);
		txInText.setTextSize( 20 );
		txOutText.setTextSize( 20 );
		DecimalFormat df = new DecimalFormat( WalletActivity.decimalString( decimalpoints ) );
		txInText.setTextColor( Color.BLACK );
		txOutText.setTextColor( Color.RED );

		v.setTag(transactions[position]);
		v.setOnClickListener( context );

		switch ( layoutStyle )
		{
			case STYLE_DETAILED:
				if ( transactions[position].amountin > 0 )
				{
					txInText.setText( df.format( transactions[position].amountin / BalanceRetriever.SATOSHIS_PER_BITCOIN ) );
				}
				else
				{
					txInText.setText("");
				}
				
				if ( transactions[position].amountout > 0)
				{
					txOutText.setText( df.format( transactions[position].amountout / BalanceRetriever.SATOSHIS_PER_BITCOIN ) );
				}
				else
				{
					txOutText.setText("");
				}
				break;
			case STYLE_SIDEBYSIDE:
				if ( transactions[position].amount >= 0 )
				{
					txInText.setText( df.format( transactions[position].amount / BalanceRetriever.SATOSHIS_PER_BITCOIN ) );
					txOutText.setText("");
				}
				else
				{
					txOutText.setText( df.format( transactions[position].amount / BalanceRetriever.SATOSHIS_PER_BITCOIN ) );
					txInText.setText("");
				}
				break;
			case STYLE_NORMAL:
				txOutText.setText("");
				if ( transactions[position].amount >= 0 )
				{
					txInText.setText( df.format( transactions[position].amount / BalanceRetriever.SATOSHIS_PER_BITCOIN ) );
				}
				else
				{
					txInText.setTextColor(Color.RED);
					txInText.setText( df.format( transactions[position].amount / BalanceRetriever.SATOSHIS_PER_BITCOIN ) );
				}
				break;
		}

		TextView fromText = (TextView) v.findViewById(R.id.txFromText );
		fromText.setText( transactions[position].from);
		
		TextView toText = (TextView) v.findViewById(R.id.txToText );
		toText.setText( transactions[position].to);

		TextView currentBalanceText = (TextView) v.findViewById(R.id.txBalanceText );
		currentBalanceText.setTextSize( 20 );
		currentBalanceText.setText( df.format( transactions[position].balance / BalanceRetriever.SATOSHIS_PER_BITCOIN ) );
		
		TextView txDateTextView = (TextView) v.findViewById(R.id.txDateText );
	
		txDateTextView.setTextColor( Color.GRAY );
		txDateTextView.setTextSize( 10 );
		txDateTextView.setText( DateFormat.getDateFormat(context).format(transactions[position].date ) );
		
		TextView txTimeTextView = (TextView) v.findViewById(R.id.txTimeText );
		
		txTimeTextView.setTextColor( Color.GRAY );
		txTimeTextView.setTextSize( 10 );
		txTimeTextView.setText( DateFormat.format("kk:mm", transactions[position].date ) );

		return v;
	}
}

public class TransactionsActivity extends Activity implements OnClickListener
{
	private Transaction [] transactions;

	/** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
    	Log.d("transaction", "start");
    	int style = getIntent().getExtras().getInt( WalletActivity.TRANSACTIONSTYLE );
    	int decimalpoints = getIntent().getExtras().getInt( WalletActivity.DECIMALPOINTS );
        super.onCreate(savedInstanceState);
        setContentView( R.layout.txmain );

        setTitle( getIntent().getExtras().getString( WalletActivity.WALLETNAME ) + " Transactions");

        if ( style == TransactionAdapter.STYLE_UNKNOWN )
        {
        	style = TransactionAdapter.STYLE_NORMAL;
        }

        Parcelable parcels [] = getIntent().getExtras().getParcelableArray( WalletActivity.TRANSACTIONS );
        // java can't cast an array into a supertype of the array for some reason... apparently it breaks type safety.
        transactions = new Transaction[ parcels.length ];
        for ( int i = 0; i < parcels.length; i++)
        {
        	transactions[i] = (Transaction) parcels[i];
        }

        // sort by date
//        Arrays.sort( transactions );
        // recalculate rolling balance for display
        long balance = 0;
        
        for ( int i = transactions.length - 1; i >= 0; i--)
        {
        	balance += transactions[i].amount;
        	transactions[i].balance = balance;
        }

        ListView view = (ListView) findViewById( R.id.transactionListView );
        TransactionAdapter adapter = new TransactionAdapter( this, transactions, style, decimalpoints );
        view.setAdapter( adapter );
        WalletActivity.dismissProgress();
        Log.d("transaction","finish");
    }

	@Override
	public void onClick(View v)
	{
		Transaction tx = (Transaction) v.getTag();

		Uri uri = Uri.parse( "http://blockexplorer.com/address/" + tx.to );
		startActivity( new Intent( Intent.ACTION_VIEW, uri ) );
	}
}