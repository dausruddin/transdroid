/*
 *	This file is part of Transdroid <http://www.transdroid.org>
 *	
 *	Transdroid is free software: you can redistribute it and/or modify
 *	it under the terms of the GNU General Public License as published by
 *	the Free Software Foundation, either version 3 of the License, or
 *	(at your option) any later version.
 *	
 *	Transdroid is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *	GNU General Public License for more details.
 *	
 *	You should have received a copy of the GNU General Public License
 *	along with Transdroid.  If not, see <http://www.gnu.org/licenses/>.
 *	
 */
package org.transdroid.daemon.Qbittorrent;

import com.android.internalcopy.http.multipart.FilePart;
import com.android.internalcopy.http.multipart.MultipartEntity;
import com.android.internalcopy.http.multipart.Part;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.transdroid.core.gui.log.Log;
import org.transdroid.daemon.Daemon;
import org.transdroid.daemon.DaemonException;
import org.transdroid.daemon.DaemonException.ExceptionType;
import org.transdroid.daemon.DaemonSettings;
import org.transdroid.daemon.IDaemonAdapter;
import org.transdroid.daemon.Label;
import org.transdroid.daemon.Priority;
import org.transdroid.daemon.Torrent;
import org.transdroid.daemon.TorrentDetails;
import org.transdroid.daemon.TorrentFile;
import org.transdroid.daemon.TorrentStatus;
import org.transdroid.daemon.task.*;
import org.transdroid.daemon.util.HttpHelper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The daemon adapter for the qBittorrent torrent client.
 * @author erickok
 */
public class QbittorrentAdapter implements IDaemonAdapter {

	private static final String LOG_NAME = "qBittorrent daemon";

	private DaemonSettings settings;
	private DefaultHttpClient httpclient;
	private int version = -1;
	private float apiVersion = -1; // starting from 2.3 old API is dropped so we are going to use float
	private int http_response_code = -1;

	public QbittorrentAdapter(DaemonSettings settings) {
		this.settings = settings;
	}

	private synchronized void ensureVersion(Log log) throws DaemonException {
		// Still need to retrieve the API and qBittorrent version numbers from the server?
		if (version > 0 && apiVersion > 0)
			return;

		try {

			// Since 4.2.0, old API is dropped. Fallback to old one if the new one failed for version <4.2.0
			// The API version is only supported since qBittorrent 3.2, so otherwise we assume version 1

			try {
				String apiVerText = makeRequest(log, "/api/v2/app/webapiVersion", new BasicNameValuePair("username", settings.getUsername()),
						new BasicNameValuePair("password", settings.getPassword()));
				apiVersion = Float.parseFloat(apiVerText.trim());
			} catch (DaemonException | NumberFormatException e) {
				if (http_response_code == 403) {
					try {
						ensureAuthenticated(log);
						String apiVerText = makeRequest(log, "/api/v2/app/webapiVersion");
						apiVersion = Float.parseFloat(apiVerText.trim());
					} catch (DaemonException | NumberFormatException e2) {
						apiVersion = (float) 2.3; // assume this is new API since we are forbidden to access API
					}
				} else {
					try {
						String apiVerText = makeRequest(log, "/version/api");
						apiVersion = Float.parseFloat(apiVerText.trim());
					} catch (DaemonException | NumberFormatException e3) {
						apiVersion = 1;
					}
				}
			}
			log.d(LOG_NAME, "qBittorrent API version is " + apiVersion);

			// The qBittorent version is only supported since 3.2; for earlier versions we parse the about dialog and parse it
			// Since 4.2.0, new API version is used instead
			String versionText = "";
			if (apiVersion >= (float) 2.3) {
				ensureAuthenticated(log);
				versionText = makeRequest(log, "/api/v2/app/version").substring(1);
			} else if (apiVersion > (float) 1) {
				// Format is something like 'v3.2.0'
				versionText = makeRequest(log, "/version/qbittorrent").substring(1);
			} else {
				// Format is something like 'qBittorrent v2.9.7 (Web UI)' or 'qBittorrent v3.0.0-alpha5 (Web UI)'
				String about = makeRequest(log, "/about.html");
				String aboutStartText = "qBittorrent v";
				String aboutEndText = " (Web UI)";
				int aboutStart = about.indexOf(aboutStartText);
				int aboutEnd = about.indexOf(aboutEndText);
				if (aboutStart >= 0 && aboutEnd > aboutStart) {
					versionText = about.substring(aboutStart + aboutStartText.length(), aboutEnd);
				}
			}
			log.d(LOG_NAME, "qBittorrent client version is " + versionText);

			// String found: now parse a version like 2.9.7 as a number like 20907 (allowing 10 places for each .)
			String[] parts = versionText.split("\\.");
			if (parts.length > 0) {
				version = Integer.parseInt(parts[0]) * 100 * 100;
				if (parts.length > 1) {
					version += Float.parseFloat(parts[1]) * 100;
					if (parts.length > 2) {
						// For the last part only read until a non-numeric character is read
						// For example version 3.0.0-alpha5 is read as version code 30000
						String numbers = "";
						for (char c : parts[2].toCharArray()) {
							if (Character.isDigit(c))
								// Still a number; add it to the numbers string
								numbers += Character.toString(c);
							else {
								// No longer reading numbers; stop reading
								break;
							}
						}
						version += Float.parseFloat(numbers);
					}
				}
			}

		} catch (Exception e) {
			// Unable to establish version number; assume an old version by setting it to version 1
			version = 10000;
			apiVersion = 1;
		}

	}

	private synchronized void ensureAuthenticated(Log log) throws DaemonException {
		// API changed in 3.2.0, login is now handled by its own request, which provides you a cookie.
		// If we don't have that cookie, let's try and get it.

		if (apiVersion < (float) 2) {
			return;
		}

		// Have we already authenticated?  Check if we have the cookie that we need
		List<Cookie> cookies = httpclient.getCookieStore().getCookies();
		for (Cookie c : cookies) {
			if (c.getName().equals("SID")) {
				// And here it is!  Okay, no need authenticate again.
				return;
			}
		}

		if (apiVersion >= (float) 2.3) {
			makeRequest(log, "/api/v2/auth/login", new BasicNameValuePair("username", settings.getUsername()),
					new BasicNameValuePair("password", settings.getPassword()));
		} else {
			makeRequest(log, "/login", new BasicNameValuePair("username", settings.getUsername()),
					new BasicNameValuePair("password", settings.getPassword()));
		}
		// The HttpClient will automatically remember the cookie for us, no need to parse it out.

		// However, we would like to see if authentication was successful or not... 
		cookies = httpclient.getCookieStore().getCookies();
		for (Cookie c : cookies) {
			if (c.getName().equals("SID")) {
				// Good.  Let's get out of here.
				return;
			}
		}

		// No cookie found, we didn't authenticate.
		throw new DaemonException(ExceptionType.AuthenticationFailure, "Server rejected our login");
	}

	@Override
	public DaemonTaskResult executeTask(Log log, DaemonTask task) {

		try {
			ensureVersion(log);
			ensureAuthenticated(log);

			switch (task.getMethod()) {
				case Retrieve:

					// Request all torrents from server
					String path;
					if (version >= 40200) {
						path = "/api/v2/torrents/info";
					} else if (version >= 30200) {
						path = "/query/torrents";
					} else if (version >= 30000) {
						path = "/json/torrents";
					} else {
						path = "/json/events";
					}

					JSONArray result = new JSONArray(makeRequest(log, path));

					return new RetrieveTaskSuccessResult((RetrieveTask) task, parseJsonTorrents(result), parseJsonLabels(result));

				case GetTorrentDetails:

					// Request tracker and error details for a specific teacher
					String mhash = task.getTargetTorrent().getUniqueID();
					JSONArray messages;
					JSONArray pieces;
					if (version >= 40200) {
						messages = new JSONArray(makeRequest(log, "/api/v2/torrents/trackers", new BasicNameValuePair("hash", mhash)));
						pieces = new JSONArray(makeRequest(log, "/api/v2/torrents/pieceStates",  new BasicNameValuePair("hash", mhash)));
					} else {
						messages = new JSONArray(makeRequest(log, "/query/propertiesTrackers/" + mhash));
						pieces = new JSONArray(makeRequest(log, "/query/getPieceStates/" + mhash));
					}

					return new GetTorrentDetailsTaskSuccessResult((GetTorrentDetailsTask) task, parseJsonTorrentDetails(messages, pieces));

				case GetFileList:

					// Request files listing for a specific torrent
					String fhash = task.getTargetTorrent().getUniqueID();
					JSONArray files;
					if (version >= 40200) {
						files = new JSONArray(makeRequest(log, "/api/v2/torrents/files", new BasicNameValuePair("hash", fhash)));
					} else if (version >= 30200) {
						files = new JSONArray(makeRequest(log, "/query/propertiesFiles/" + fhash));
					} else {
						files = new JSONArray(makeRequest(log, "/json/propertiesFiles/" + fhash));
					}

					return new GetFileListTaskSuccessResult((GetFileListTask) task, parseJsonFiles(files));

				case AddByFile:

					// Upload a local .torrent file
					if (version >= 40200) {
						path = "/api/v2/torrents/add";
					} else {
						path = "/command/upload";
					}

					String ufile = ((AddByFileTask) task).getFile();
					makeUploadRequest(path, ufile, log);
					return new DaemonTaskSuccessResult(task);

				case AddByUrl:

					// Request to add a torrent by URL
					String url = ((AddByUrlTask) task).getUrl();
					if (version >= 40200) {
						path = "/api/v2/torrents/add";
					} else {
						path = "/command/upload";
					}

					makeRequest(log, path, new BasicNameValuePair("urls", url));
					return new DaemonTaskSuccessResult(task);

				case AddByMagnetUrl:

					// Request to add a magnet link by URL
					String magnet = ((AddByMagnetUrlTask) task).getUrl();
					if (version >= 40200) {
						path = "/api/v2/torrents/add";
					} else {
						path = "/command/download";
					}

					makeRequest(log, path, new BasicNameValuePair("urls", magnet));
					return new DaemonTaskSuccessResult(task);

				case Remove:

					// Remove a torrent
					RemoveTask removeTask = (RemoveTask) task;
					if (version >= 40200) {
						if (removeTask.includingData()) {
							makeRequest(log, "/api/v2/torrents/delete",
									new BasicNameValuePair("hashes", removeTask.getTargetTorrent().getUniqueID()),
									new BasicNameValuePair("deleteFiles", "true"));
						} else {
							makeRequest(log, "/api/v2/torrents/delete",
									new BasicNameValuePair("hashes", removeTask.getTargetTorrent().getUniqueID()),
									new BasicNameValuePair("deleteFiles", "false"));
						}

					} else {
						path = (removeTask.includingData() ? "/command/deletePerm" : "/command/delete");
						makeRequest(log, path, new BasicNameValuePair("hashes", removeTask.getTargetTorrent().getUniqueID()));
					}

					return new DaemonTaskSuccessResult(task);

				case Pause:

					// Pause a torrent
					if (version >= 40200) {
						makeRequest(log, "/api/v2/torrents/pause", new BasicNameValuePair("hashes", task.getTargetTorrent().getUniqueID()));
					} else {
						makeRequest(log, "/command/pause", new BasicNameValuePair("hash", task.getTargetTorrent().getUniqueID()));
					}

					return new DaemonTaskSuccessResult(task);

				case PauseAll:

					// Resume all torrents
					if (version >= 40200) {
						makeRequest(log, "/api/v2/torrents/pause", new BasicNameValuePair("hashes", "all"));
					} else {
						makeRequest(log, "/command/pauseall");
					}

					return new DaemonTaskSuccessResult(task);

				case Resume:

					// Resume a torrent
					if (version >= 40200) {
						makeRequest(log, "/api/v2/torrents/resume", new BasicNameValuePair("hashes", task.getTargetTorrent().getUniqueID()));
					} else {
						makeRequest(log, "/command/resume", new BasicNameValuePair("hash", task.getTargetTorrent().getUniqueID()));
					}

					return new DaemonTaskSuccessResult(task);

				case ResumeAll:

					// Resume all torrents
					if (version >= 40200) {
						path = "/api/v2/torrents/resume";
						makeRequest(log, path, new BasicNameValuePair("hashes", "all"));
					} else {
						makeRequest(log, "/command/resumeall");
					}

					return new DaemonTaskSuccessResult(task);

				case SetFilePriorities:

					// Update the priorities to a set of files
					SetFilePriorityTask setPrio = (SetFilePriorityTask) task;
					String newPrio = "0";
					if (setPrio.getNewPriority() == Priority.Low) {
						newPrio = "1";
					} else if (setPrio.getNewPriority() == Priority.Normal) {
						newPrio = "2";
					} else if (setPrio.getNewPriority() == Priority.High) {
						newPrio = "7";
					}
					// We have to make a separate request per file, it seems
					for (TorrentFile file : setPrio.getForFiles()) {
						if (version >= 40200) {
							path = "/api/v2/torrents/filePrio";
						} else {
							path = "/command/setFilePrio";
						}
						makeRequest(log, path, new BasicNameValuePair("hash", task.getTargetTorrent().getUniqueID()),
								new BasicNameValuePair("id", file.getKey()), new BasicNameValuePair("priority", newPrio));

					}
					return new DaemonTaskSuccessResult(task);

				case ForceRecheck:

						// Force recheck a torrent
					if (version >= 40200) {
						path = "/api/v2/torrents/recheck";
					} else {
						path = "/command/recheck";
					}
					makeRequest(log, path, new BasicNameValuePair("hash", task.getTargetTorrent().getUniqueID()));
					return new DaemonTaskSuccessResult(task);

				case ToggleSequentialDownload:

					// Toggle sequential download mode on a torrent
					if (version >= 40200) {
						path = "/api/v2/torrents/toggleSequentialDownload";
					} else {
						path = "/command/toggleSequentialDownload";
					}
					makeRequest(log, path, new BasicNameValuePair("hashes", task.getTargetTorrent().getUniqueID()));
					return new DaemonTaskSuccessResult(task);

				case ToggleFirstLastPieceDownload:

					// Set policy for downloading first and last piece first on a torrent
					if (version >= 40200) {
						path = "/api/v2/torrents/toggleFirstLastPiecePrio";
					} else {
						path = "/command/toggleFirstLastPiecePrio";
					}
					makeRequest(log, path, new BasicNameValuePair("hashes", task.getTargetTorrent().getUniqueID()));
					return new DaemonTaskSuccessResult(task);

				case SetLabel:

					SetLabelTask labelTask = (SetLabelTask) task;
					if (version >= 40200) {
						path = "/api/v2/torrents/setCategory";
					} else {
						path = "/command/setCategory";
					}
					makeRequest(log, path,
							new BasicNameValuePair("hashes", task.getTargetTorrent().getUniqueID()),
							new BasicNameValuePair("category", labelTask.getNewLabel()));
					return new DaemonTaskSuccessResult(task);

				case SetDownloadLocation:

					SetDownloadLocationTask setLocationTask = (SetDownloadLocationTask) task;
					if (version >= 40200) {
						path = "/api/v2/torrents/setLocation";
					} else {
						path = "/command/setLocation";
					}
					makeRequest(log, path,
							new BasicNameValuePair("hashes", task.getTargetTorrent().getUniqueID()),
							new BasicNameValuePair("location", setLocationTask.getNewLocation()));
					return new DaemonTaskSuccessResult(task);

				case SetTransferRates:

					// Request to set the maximum transfer rates
					String pathDL;
					String pathUL;
					SetTransferRatesTask ratesTask = (SetTransferRatesTask) task;
					String dl = (ratesTask.getDownloadRate() == null ? "NaN" : Long.toString(ratesTask.getDownloadRate() * 1024));
					String ul = (ratesTask.getUploadRate() == null ? "NaN" : Long.toString(ratesTask.getUploadRate() * 1024));

					if (version >= 40200) {
						pathDL = "/api/v2/torrents/setDownloadLimit";
						pathUL = "/api/v2/torrents/setUploadLimit";
					} else {
						pathDL = "/command/setGlobalDlLimit";
						pathUL = "/command/setGlobalUpLimit";
					}

					makeRequest(log, pathDL, new BasicNameValuePair("limit", dl));
					makeRequest(log, pathUL, new BasicNameValuePair("limit", ul));
					return new DaemonTaskSuccessResult(task);

				case GetStats:

					// Refresh alternative download speeds setting
					if (version >= 40200) {
						path = "/api/v2/sync/maindata?rid=0";
					} else {
						path = "/sync/maindata?rid=0";
					}
					JSONObject stats = new JSONObject(makeRequest(log, path));
					JSONObject serverStats = stats.optJSONObject("server_state");
					boolean alternativeSpeeds = false;
					if (serverStats != null) {
						alternativeSpeeds = serverStats.optBoolean("use_alt_speed_limits");
					}
					return new GetStatsTaskSuccessResult((GetStatsTask) task, alternativeSpeeds, -1);

				case SetAlternativeMode:

					// Flip alternative speed mode
					if (version >= 40200) {
						path = "/api/v2/transfer/toggleSpeedLimitsMode";
					} else {
						path = "/command/toggleAlternativeSpeedLimits";
					}
					makeRequest(log, path);
					return new DaemonTaskSuccessResult(task);

				default:
					return new DaemonTaskFailureResult(task,
							new DaemonException(ExceptionType.MethodUnsupported, task.getMethod() + " is not supported by " + getType()));
			}
		} catch (JSONException e) {
			return new DaemonTaskFailureResult(task, new DaemonException(ExceptionType.ParsingFailed, e.toString()));
		} catch (DaemonException e) {
			return new DaemonTaskFailureResult(task, e);
		}
	}

	private String makeRequest(Log log, String path, NameValuePair... params) throws DaemonException {

		try {

			// Setup request using POST
			String url_to_request = buildWebUIUrl(path);
			HttpPost httppost = new HttpPost(url_to_request);
			log.d(LOG_NAME, "URL to request: "+ url_to_request);
			
			List<NameValuePair> nvps = new ArrayList<>();
			Collections.addAll(nvps, params);
			httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
			return makeWebRequest(httppost, log);

		} catch (UnsupportedEncodingException e) {
			throw new DaemonException(ExceptionType.ConnectionError, e.toString());
		}

	}

	private String makeUploadRequest(String path, String file, Log log) throws DaemonException {

		try {

			// Setup request using POST
			HttpPost httppost = new HttpPost(buildWebUIUrl(path));
			File upload = new File(URI.create(file));
			Part[] parts = {new FilePart("torrentfile", upload)};
			httppost.setEntity(new MultipartEntity(parts, httppost.getParams()));
			return makeWebRequest(httppost, log);

		} catch (FileNotFoundException e) {
			throw new DaemonException(ExceptionType.FileAccessError, e.toString());
		}

	}

	private String makeWebRequest(HttpRequestBase httpmethod, Log log) throws DaemonException {

		try {

			// Initialise the HTTP client
			if (httpclient == null) {
				initialise();
			}

			// Execute
			HttpResponse response = httpclient.execute(httpmethod);
			http_response_code = response.getStatusLine().getStatusCode();
			log.d(LOG_NAME, "Response code is: " + http_response_code);

			HttpEntity entity = response.getEntity();
			if (entity != null) {

				// Read JSON response
				java.io.InputStream instream = entity.getContent();
				String result = HttpHelper.convertStreamToString(instream);
				instream.close();

				// TLog.d(LOG_NAME, "Success: " + (result.length() > 300? result.substring(0, 300) + "... (" +
				// result.length() + " chars)": result));

				// Return raw result
				return result;
			}

			log.d(LOG_NAME, "Error: No entity in HTTP response");
			throw new DaemonException(ExceptionType.UnexpectedResponse, "No HTTP entity object in response.");

		} catch (Exception e) {
			log.d(LOG_NAME, "Error: " + e.toString());
			throw new DaemonException(ExceptionType.ConnectionError, e.toString());
		}

	}

	/**
	 * Instantiates an HTTP client with proper credentials that can be used for all qBittorrent requests.
	 * @throws DaemonException On conflicting or missing settings
	 */
	private void initialise() throws DaemonException {
		httpclient = HttpHelper.createStandardHttpClient(settings, true);
	}

	/**
	 * Build the URL of the web UI request from the user settings
	 * @return The URL to request
	 */
	private String buildWebUIUrl(String path) {
		String proxyFolder = settings.getFolder();
		if (proxyFolder == null)
			proxyFolder = "";
		else if (proxyFolder.endsWith("/"))
			proxyFolder = proxyFolder.substring(0, proxyFolder.length() - 1);
		return (settings.getSsl() ? "https://" : "http://") + settings.getAddress() + ":" + settings.getPort() + proxyFolder + path;
	}

	private TorrentDetails parseJsonTorrentDetails(JSONArray messages, JSONArray pieceStates) throws JSONException {

		ArrayList<String> trackers = new ArrayList<>();
		ArrayList<String> errors = new ArrayList<>();

		// Parse response
		if (messages.length() > 0) {
			for (int i = 0; i < messages.length(); i++) {
				JSONObject tor = messages.getJSONObject(i);
				trackers.add(tor.getString("url"));
				String msg = tor.getString("msg");
				if (msg != null && !msg.equals(""))
					errors.add(msg);
			}
		}

                ArrayList<Integer> pieces = new ArrayList<>();
                if (pieceStates.length() > 0) {
		        for (int i = 0; i < pieceStates.length(); i++) {
                               pieces.add(pieceStates.getInt(i));
                        }
                }

		// Return the list
		return new TorrentDetails(trackers, errors, pieces);

	}

	private List<Label> parseJsonLabels(JSONArray response) throws JSONException {

		// Collect used labels from response
		Map<String, Label> labels = new HashMap<>();
		for (int i = 0; i < response.length(); i++) {
			JSONObject tor = response.getJSONObject(i);
			if (apiVersion >= 2) {
				String label = tor.optString("category");
				if (label != null && label.length() > 0) {
					final Label labelObject = labels.get(label);
					labels.put(label, new Label(label, (labelObject != null) ? labelObject.getCount() + 1 : 1));
				}
			}
		}
		return new ArrayList<>(labels.values());

	}

	private ArrayList<Torrent> parseJsonTorrents(JSONArray response) throws JSONException {

		// Parse response
		ArrayList<Torrent> torrents = new ArrayList<>();
		for (int i = 0; i < response.length(); i++) {
			JSONObject tor = response.getJSONObject(i);
			double progress = tor.getDouble("progress");
			int leechers[];
			int seeders[];
			double ratio;
			long size;
			long uploaded;
			int dlspeed;
			int upspeed;
                        boolean dlseq = false;
                        boolean dlflp = false;
			Date addedOn = null;
			Date completionOn = null;
			String label = null;

			if (apiVersion >= 2) {
				leechers = new int[2];
				leechers[0] = tor.getInt("num_leechs");
				leechers[1] = tor.getInt("num_complete") + tor.getInt("num_incomplete");
				seeders = new int[2];
				seeders[0] = tor.getInt("num_seeds");
				seeders[1] = tor.getInt("num_complete");
				size = tor.getLong("size");
				ratio = tor.getDouble("ratio");
				dlspeed = tor.getInt("dlspeed");
				upspeed = tor.getInt("upspeed");
                                if (tor.has("seq_dl")) {
                                    dlseq = tor.getBoolean("seq_dl");
                                }
                                if (tor.has("f_l_piece_prio")) {
                                    dlflp = tor.getBoolean("f_l_piece_prio");
                                }
				if (tor.has("uploaded")) {
					uploaded = tor.getLong("uploaded");
				} else {
					uploaded = (long) (size * ratio);
				}
				final long addedOnTime = tor.optLong("added_on");
				addedOn = (addedOnTime > 0) ? new Date(addedOnTime * 1000L) : null;
				final long completionOnTime = tor.optLong("completion_on");
				completionOn = (completionOnTime > 0) ? new Date(completionOnTime * 1000L) : null;
				label = tor.optString("category");
				if (label.length() == 0) {
					label = null;
				}
			} else {
				leechers = parsePeers(tor.getString("num_leechs"));
				seeders = parsePeers(tor.getString("num_seeds"));
				size = parseSize(tor.getString("size"));
				ratio = parseRatio(tor.getString("ratio"));
				uploaded = (long) (size * ratio);
				dlspeed = parseSpeed(tor.getString("dlspeed"));
				upspeed = parseSpeed(tor.getString("upspeed"));
			}

			long eta = -1L;
			if (dlspeed > 0)
				eta = (long) (size - (size * progress)) / dlspeed;
			// Add the parsed torrent to the list
			// @formatter:off
                        Torrent torrent = new Torrent(
					(long) i,
					tor.getString("hash"),
					tor.getString("name"),
					parseStatus(tor.getString("state")),
					null,
					dlspeed,
					upspeed,
					seeders[0],
					seeders[1],
					leechers[0],
					leechers[1],
					(int) eta,
					(long) (size * progress),
					uploaded,
					size,
					(float) progress,
					0f,
					label,
					addedOn,
					completionOn,
					null,
					settings.getType());
                        torrent.mimicSequentialDownload(dlseq);
                        torrent.mimicFirstLastPieceDownload(dlflp);
			torrents.add(torrent);
			// @formatter:on
		}

		// Return the list
		return torrents;

	}

	private double parseRatio(String string) {
		// Ratio is given in "1.5" string format
		try {
			return Double.parseDouble(normalizeNumber(string));
		} catch (Exception e) {
			return 0D;
		}
	}

	private long parseSize(String string) {
		// See https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-Documentation
		if (string.equals("Unknown"))
			return -1;
		// Sizes are given in "1,023.3 MiB"-like string format
		String[] parts = string.split(" ");
		double number;
		try {
			number = Double.parseDouble(normalizeNumber(parts[0]));
		} catch (Exception e) {
			return -1L;
		}
		// Returns size in B-based long
		if (parts[1].equals("TiB")) {
			return (long) (number * 1024L * 1024L * 1024L * 1024L);
		} else if (parts[1].equals("GiB")) {
			return (long) (number * 1024L * 1024L * 1024L);
		} else if (parts[1].equals("MiB")) {
			return (long) (number * 1024L * 1024L);
		} else if (parts[1].equals("KiB")) {
			return (long) (number * 1024L);
		}
		return (long) number;
	}

	private int[] parsePeers(String seeds) {
		// Peers (seeders or leechers) are defined in a string like "num_seeds":"6 (27)"
		// In some situations it it just a "6" string
		String[] parts = seeds.split(" ");
		if (parts.length > 1) {
			return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1].substring(1, parts[1].length() - 1))};
		}
		return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[0])};
	}

	private int parseSpeed(String speed) {
		// See https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-Documentation
		if (speed.equals("Unknown"))
			return -1;
		// Sizes are given in "1,023.3 KiB/s"-like string format
		String[] parts = speed.split(" ");
		double number;
		try {
			number = Double.parseDouble(normalizeNumber(parts[0]));
		} catch (Exception e) {
			return -1;
		}
		// Returns size in B-based int
		if (parts[1].equals("GiB/s")) {
			return (int) (number * 1024 * 1024 * 1024);
		} else if (parts[1].equals("MiB/s")) {
			return (int) (number * 1024 * 1024);
		} else if (parts[1].equals("KiB/s")) {
			return (int) (number * 1024);
		}
		return (int) (Double.parseDouble(normalizeNumber(parts[0])));
	}

	private String normalizeNumber(String in) {
		// FIXME Hack for issue #115: Strip the possible . and , separators in a hopefully reliable fashion, for now
		if (in.length() >= 3) {
			String part1 = in.substring(0, in.length() - 3);
			String part2 = in.substring(in.length() - 3);
			return part1.replace("Ê", "").replace(" ", "").replace(",", "").replace(".", "") + part2.replace(",", ".");
		}
		return in.replace(",", ".");
	}

	private TorrentStatus parseStatus(String state) {
		// Status is given as a descriptive string
		if (state.equals("error")) {
			return TorrentStatus.Error;
		} else if (state.equals("downloading") || state.equals("metaDL")) {
			return TorrentStatus.Downloading;
		} else if (state.equals("uploading")) {
			return TorrentStatus.Seeding;
		} else if (state.equals("pausedDL")) {
			return TorrentStatus.Paused;
		} else if (state.equals("pausedUP")) {
			return TorrentStatus.Paused;
		} else if (state.equals("stalledUP")) {
			return TorrentStatus.Seeding;
		} else if (state.equals("stalledDL")) {
			return TorrentStatus.Downloading;
		} else if (state.equals("checkingUP")) {
			return TorrentStatus.Checking;
		} else if (state.equals("checkingDL")) {
			return TorrentStatus.Checking;
		} else if (state.equals("queuedDL")) {
			return TorrentStatus.Queued;
		} else if (state.equals("queuedUP")) {
			return TorrentStatus.Queued;
		}
		return TorrentStatus.Unknown;
	}

	private ArrayList<TorrentFile> parseJsonFiles(JSONArray response) throws JSONException {

		// Parse response
		ArrayList<TorrentFile> torrentfiles = new ArrayList<>();
		for (int i = 0; i < response.length(); i++) {
			JSONObject file = response.getJSONObject(i);

			long size;
			if (apiVersion >= 2) {
				size = file.getLong("size");
			} else {
				size = parseSize(file.getString("size"));
			}

			torrentfiles.add(new TorrentFile("" + i, file.getString("name"), null, null, size, (long) (size * file.getDouble("progress")),
					parsePriority(file.getInt("priority"))));
		}

		// Return the list
		return torrentfiles;

	}

	private Priority parsePriority(int priority) {
		// Priority is an integer
		// Actually 1 = Normal, 2 = High, 7 = Maximum, but adjust this to Transdroid values
		if (priority == 0) {
			return Priority.Off;
		} else if (priority == 1) {
			return Priority.Low;
		} else if (priority == 2) {
			return Priority.Normal;
		}
		return Priority.High;
	}

	@Override
	public Daemon getType() {
		return settings.getType();
	}

	@Override
	public DaemonSettings getSettings() {
		return this.settings;
	}

}
