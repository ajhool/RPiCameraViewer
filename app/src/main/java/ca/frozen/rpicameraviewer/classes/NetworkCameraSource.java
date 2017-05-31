// Copyright © 2016 Shawn Baker using the MIT License.
package ca.frozen.rpicameraviewer.classes;

import android.os.Parcel;
import android.os.Parcelable;

import org.json.JSONException;
import org.json.JSONObject;

public class NetworkCameraSource implements Comparable, Parcelable
{
	// local constants
	private final static String TAG = "NetworkCameraSource";

	// instance variables
	public String network;
	public String name;
	public Source source;

	//******************************************************************************
	// NetworkCameraSource
	//******************************************************************************
    public NetworkCameraSource(Source.ConnectionType connectionType, String network, String address, int port) {
		this.network = network;
      this.name = "";
		this.source = new Source(connectionType, address, port);
		//Log.d(TAG, "address/source: " + toString());
	}

	//******************************************************************************
	// NetworkCameraSource
	//******************************************************************************
	public NetworkCameraSource(String name, Source source)
	{
		network = Utils.getNetworkName();
		this.name = name;
		this.source = new Source(source.connectionType, "", source.port);
		//Log.d(TAG, "name/source: " + toString());
	}

	//******************************************************************************
	// NetworkCameraSource
	//******************************************************************************
	public NetworkCameraSource(NetworkCameraSource networkCameraSource)
	{
		network = networkCameraSource.network;
		name = networkCameraSource.name;
		source = new Source(networkCameraSource.source);
		//Log.d(TAG, "networkCameraSource: " + toString());
	}

	//******************************************************************************
	// NetworkCameraSource
	//******************************************************************************
	public NetworkCameraSource(Parcel in)
	{
		readFromParcel(in);
		//Log.d(TAG, "parcel: " + toString());
	}

	//******************************************************************************
	// NetworkCameraSource
	//******************************************************************************
	public NetworkCameraSource(JSONObject obj)
	{
		try
		{
			network = obj.getString("network");
			name = obj.getString("name");
			source = new Source(obj.getJSONObject("source"));
		}
		catch (JSONException ex)
		{
			initialize();
		}
		//Log.d(TAG, "json: " + toString());
	}

	//******************************************************************************
	// initialize
	//******************************************************************************
	private void initialize()
	{
		network = Utils.getNetworkName();
		name = Utils.getDefaultCameraName();
		source = new Source(Utils.getSettings().rawTcpIpSource);
		source.address = Utils.getBaseIpAddress();
	}

	//******************************************************************************
	// writeToParcel
	//******************************************************************************
	@Override
	public void writeToParcel(Parcel dest, int flags)
	{
		dest.writeString(network);
		dest.writeString(name);
		dest.writeParcelable(source, flags);
	}

	//******************************************************************************
	// readFromParcel
	//******************************************************************************
	private void readFromParcel(Parcel in)
	{
		network = in.readString();
		name = in.readString();
		source = in.readParcelable(Source.class.getClassLoader());
	}

	//******************************************************************************
	// describeContents
	//******************************************************************************
	public int describeContents()
	{
		return 0;
	}

	//******************************************************************************
	// Parcelable.Creator
	//******************************************************************************
	public static final Parcelable.Creator CREATOR = new Parcelable.Creator()
	{
		public NetworkCameraSource createFromParcel(Parcel in)
		{
			return new NetworkCameraSource(in);
		}
		public NetworkCameraSource[] newArray(int size)
		{
			return new NetworkCameraSource[size];
		}
	};

	//******************************************************************************
	// equals
	//******************************************************************************
    @Override
    public boolean equals(Object otherCamera)
    {
		return compareTo(otherCamera) == 0;
    }

	//******************************************************************************
	// compareTo
	//******************************************************************************
    @Override
    public int compareTo(Object otherCamera)
    {
		int result = 1;
		if (otherCamera instanceof NetworkCameraSource)
		{
			NetworkCameraSource networkCameraSource = (NetworkCameraSource) otherCamera;
			result = name.compareTo(networkCameraSource.name);
			if (result == 0)
			{
				result = source.compareTo(networkCameraSource.source);
				if (result == 0)
				{
					result = network.compareTo(networkCameraSource.network);
				}
			}
		}
        return result;
    }

	//******************************************************************************
	// toString
	//******************************************************************************
    @Override
    public String toString()
    {
        return name + "," + network + "," + source.toString();
    }

	//******************************************************************************
	// toJson
	//******************************************************************************
	public JSONObject toJson()
	{
		try
		{
			JSONObject obj = new JSONObject();
			obj.put("network", network);
			obj.put("name", name);
			obj.put("source", source.toJson());
			return obj;
		}
		catch(JSONException ex)
		{
			ex.printStackTrace();
		}
		return null;
	}

	//******************************************************************************
	// getCombinedSource
	//******************************************************************************
	public Source getCombinedSource()
	{
		return Utils.getSettings().getSource(source.connectionType).combine(source);
	}
}
