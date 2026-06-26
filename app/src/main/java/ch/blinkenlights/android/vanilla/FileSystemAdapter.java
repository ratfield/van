package ch.blinkenlights.android.vanilla;

import android.content.Context;
import android.content.Intent;
import android.os.FileObserver;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Pattern;

public class FileSystemAdapter extends SortableAdapter implements LibraryAdapter {
	private final java.util.HashMap<String, String> mFolderCache = new java.util.HashMap<>();
	private final java.util.HashMap<String, android.graphics.Bitmap> mCoverCache = new java.util.HashMap<>();
		// НАШ НОВЫЙ КОД: Описание структуры ViewHolder, которую мы случайно пропустили
	public static class ViewHolder {
		public int id;
		public String title;
	}
	
	private static final Pattern SPACE_SPLIT = Pattern.compile("\\s+");
	private static final Pattern FILE_SEPARATOR = Pattern.compile(File.separator);
	private static final Pattern GUESS_MUSIC = Pattern.compile("^(.+\\.(mp3|ogg|mka|opus|flac|aac|m4a|wav))$", Pattern.CASE_INSENSITIVE);
	private static final Pattern GUESS_IMAGE = Pattern.compile("^(.+\\.(gif|jpe?g|png|bmp|tiff?|webp))$", Pattern.CASE_INSENSITIVE);

	private final File mFsRoot = new File("/");
	private static final int SORT_NAME = 0;
	private static final int SORT_SIZE = 1;
	private static final int SORT_TIME = 2;
	private static final int SORT_EXT = 3;

	private static final int[] SORT_RES_IDS = new int[] {
		R.string.filename,
		R.string.file_size,
		R.string.file_time,
		R.string.extension
	};

	final LibraryActivity mActivity;
	private final LayoutInflater mInflater;
	private Limiter mLimiter;
	private File[] mFiles;
	String[] mFilter;

	private final FilenameFilter mFileFilter = new FilenameFilter() {
    @Override
    public boolean accept(File dir, String filename) {
        if (filename.charAt(0) == '.') return false;

        File file = new File(dir, filename);
        if (file.isFile() && !GUESS_MUSIC.matcher(filename).matches()) {
            return false;
        }

        if (mFilter != null) {
            filename = filename.toLowerCase();
            for (String term : mFilter) {
                if (!filename.contains(term)) return false;
            }
        }
        return true;
    }
};

	private final Comparator<File> mFileComparator = new Comparator<File>() {
		@Override
		public int compare(File a, File b) {
			boolean aIsFolder = a.isDirectory();
			boolean bIsFolder = b.isDirectory();
			if (bIsFolder == aIsFolder) {
				int mode = aIsFolder ? SORT_NAME : getSortModeIndex();
				int order;
				switch (mode) {
					case SORT_SIZE:
						order = Long.valueOf(a.length()).compareTo(Long.valueOf(b.length()));
						break;
					case SORT_TIME:
						order = Long.valueOf(a.lastModified()).compareTo(Long.valueOf(b.lastModified()));
						break;
					case SORT_EXT:
						order = FileUtils.getFileExtension(a.getName()).compareToIgnoreCase(FileUtils.getFileExtension(b.getName()));
						break;
					case SORT_NAME:
						order = a.getName().compareToIgnoreCase(b.getName());
						break;
					default:
						throw new IllegalArgumentException("Invalid sort mode: " + mode);
				}
				return (isSortDescending() ? -1 : 1) * order;
			} else if (bIsFolder) {
				return 1;
			}
			return -1;
		}
	};

	private Observer mFileObserver;

	public FileSystemAdapter(LibraryActivity activity, Limiter limiter) {
		mActivity = activity;
		mLimiter = limiter;
		mInflater = (LayoutInflater)activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		if (limiter == null) {
			limiter = buildHomeLimiter(activity);
		}
		setLimiter(limiter);
		mSortEntries = SORT_RES_IDS;
	}

	@Override
	public Object query() {
		File file = getLimiterPath();
		if (mFileObserver == null) {
			mFileObserver = new Observer(file.getPath());
		}
		ArrayList<File> files;
		File[] readdir = file.listFiles(mFileFilter);
		if (readdir != null) {
			files = new ArrayList<File>(Arrays.asList(readdir));
		} else {
			files = FileUtils.getFallbackDirectories((Context)mActivity, file);
		}
		Collections.sort(files, mFileComparator);
		if (!mFsRoot.equals(file))
			files.add(0, new File(file, FileUtils.NAME_PARENT_FOLDER));
		return files.toArray(new File[files.size()]);
	}

	@Override
	public void commitQuery(Object data) {
		mFiles = (File[])data;
		notifyDataSetChanged();
	}

	@Override
	public void clear() {
		mFiles = null;
		notifyDataSetInvalidated();
	}

	@Override
	public int getCount() {
		if (mFiles == null) return 0;
		return mFiles.length;
	}

	@Override
	public Object getItem(int pos) {
		return mFiles[pos];
	}

	@Override
	public long getItemId(int pos) {
		return FileUtils.songIdFromFile(mFiles[pos]);
	}
	@Override
	public View getView(int pos, View convertView, ViewGroup parent) {
		DraggableRow row;
		ViewHolder holder;
		if (convertView == null) {
			row = (DraggableRow)mInflater.inflate(R.layout.draggable_row, parent, false);
			row.setupLayout(DraggableRow.LAYOUT_LISTVIEW);
			holder = new ViewHolder();
			row.setTag(holder);
		} else {
			row = (DraggableRow)convertView;
			holder = (ViewHolder)row.getTag();
		}
		final File file = mFiles[pos];
		String title = file.getName();
		holder.id = pos;
		holder.title = title;
		if (file.isDirectory() && !pointsToParentFolder(file)) {
			String durationStr = getFolderDurationString(file);
			if (durationStr != null) {
				title = title + " (" + durationStr + ")";
			}
		}
		
		row.setText(title);

		if (file.isDirectory() && !file.getName().equals("..")) {
			final String folderPath = file.getAbsolutePath();
			if (mCoverCache.containsKey(folderPath)) {
				row.getCoverView().setImageBitmap(mCoverCache.get(folderPath));
			} else {
				row.getCoverView().setImageResource(R.drawable.folder);
				final DraggableRow finalRow = row;
				final int currentHolderId = holder.id;
				
				new Thread(new Runnable() {
					@Override
					public void run() {
						File[] files = new File(folderPath).listFiles();
						if (files != null) {
							for (File f : files) {
								String name = f.getName().toLowerCase();
								if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
									try {
										android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
										options.inSampleSize = 4;
										final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), options);
										if (bmp != null) {
											mActivity.runOnUiThread(new Runnable() {
												@Override
												public void run() {
													mCoverCache.put(folderPath, bmp);
													ViewHolder vh = (ViewHolder) finalRow.getTag();
													if (vh != null && vh.id == currentHolderId) {
														finalRow.getCoverView().setImageBitmap(bmp);
													}
												}
											});
										}
										break;
									} catch (Throwable t) {}
								}
							}
						}
					}
				}).start();
			}
		} else {
			row.getCoverView().setImageResource(getImageResourceForFile(file));
		}
		    // НАШ КОД: Подсветка только играющей ПАПКИ
    try {
 android.widget.TextView textView = (android.widget.TextView) row.findViewById(R.id.text);
 if (textView != null) {
 String currentPlayingPath = null;
 // Быстро вытаскиваем путь играющего сейчас трека
 if (PlaybackService.hasInstance() && PlaybackService.get(mActivity) != null) {
 PlaybackService service = PlaybackService.get(mActivity);
 if (service.getSong(0) != null) {
 currentPlayingPath = service.getSong(0).path;
 }
 }

 String thisItemPath = file.getAbsolutePath();

 if (file.isDirectory()) {
 // --- ЛОГИКА ДЛЯ ПАПКИ ---
 if (currentPlayingPath != null && currentPlayingPath.startsWith(thisItemPath + File.separator)) {
 textView.setTextColor(android.graphics.Color.GREEN); // Красим активную папку в зеленый
 } else {
 textView.setTextAppearance(mActivity, android.R.style.TextAppearance_Widget_TextView);
 }
 } else {
 // --- ВОЗВРАЩАЕМ ФИЧУ ДЛЯ ТРЕКА ---
 // Если это файл и его путь один в один совпадает с играющей песней
 if (currentPlayingPath != null && currentPlayingPath.equals(thisItemPath)) {
 textView.setTextColor(android.graphics.Color.GREEN); // Красим текущий файл в зеленый!
 } else {
 textView.setTextAppearance(mActivity, android.R.style.TextAppearance_Widget_TextView); // Защита от залипания при скролле
 }
 }
 }
 } catch (Exception e) {
 // Полная защита от сбоев
 }

   		return row;
	}

	@Override
	public void setFilter(String filter) {
		if (filter == null) mFilter = null;
		else mFilter = SPACE_SPLIT.split(filter.toLowerCase());
	}

	@Override
	public void setLimiter(Limiter limiter) {
		if (mFileObserver != null) mFileObserver.stopWatching();
		mFileObserver = null;
		if (limiter != null && mFsRoot.equals(limiter.data)) limiter = null;
		mLimiter = limiter;
	}

	@Override
	public Limiter getLimiter() {
		return mLimiter;
	}

	private int getImageResourceForFile(File file) {
		int res = R.drawable.file_document;
		if (pointsToParentFolder(file)) {
			res = R.drawable.arrow_up;
		} else if (file.isDirectory()) {
			res = R.drawable.folder;
		} else if (GUESS_MUSIC.matcher(file.getName()).matches()) {
			res = R.drawable.file_music;
		} else if (GUESS_IMAGE.matcher(file.getName()).matches()) {
			res = R.drawable.file_image;
		}
		return res;
	}

	private File getLimiterPath() {
		return mLimiter == null ? new File("/") : (File)mLimiter.data;
	}

	private static boolean pointsToParentFolder(File file) {
		return FileUtils.NAME_PARENT_FOLDER.equals(file.getName());
	}

	public static Limiter buildLimiter(File file) {
		if (pointsToParentFolder(file)) file = file.getParentFile().getParentFile();
		String[] fields = FILE_SEPARATOR.split(file.getPath().substring(1));
		return new Limiter(MediaUtils.TYPE_FILE, fields, file);
	}

	public static Limiter buildHomeLimiter(Context context) {
		return buildLimiter(FileUtils.getFilesystemBrowseStart(context));
	}

	@Override
	public Limiter buildLimiter(long id) {
		for (int i = 0; i < mFiles.length; i++) {
			if (id == getItemId(i)) {
				return buildLimiter(mFiles[i]);
			}
		}
		return null;
	}

	@Override
	public int getMediaType() {
		return MediaUtils.TYPE_FILE;
	}

	private class Observer extends FileObserver {
		public Observer(String path) {
			super(path, FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM);
			startWatching();
		}
		@Override
		public void onEvent(int event, String path) {
			if (path != null) mActivity.mPagerAdapter.postRequestRequery(FileSystemAdapter.this);
		}
	}

	@Override
	public Intent createData(View view) {
		ViewHolder holder = (ViewHolder)view.getTag();
		File file = mFiles[(int)holder.id];
		Intent intent = new Intent();
		intent.putExtra(LibraryAdapter.DATA_TYPE, MediaUtils.TYPE_FILE);
		intent.putExtra(LibraryAdapter.DATA_ID, getItemId((int)holder.id));
		intent.putExtra(LibraryAdapter.DATA_TITLE, holder.title);
		intent.putExtra(LibraryAdapter.DATA_EXPANDABLE, file.isDirectory());
		intent.putExtra(LibraryAdapter.DATA_FILE, file.getAbsolutePath());
		return intent;
	}

	@Override
	public int getDefaultSortMode() {
		return SORT_NAME;
	}

	@Override
	public String getSortSettingsKey() {
		return "sort_filesystem";
	}

	@Override
	public QueryTask buildSongQuery(String[] projection) {
		File path = getLimiterPath();
		return MediaUtils.buildFileQuery(path.getPath(), projection, true);
	}

	public void onItemClicked(Intent intent) {
		boolean isFolder = intent.getBooleanExtra(LibraryAdapter.DATA_EXPANDABLE, false);
		boolean isHeader = intent.getLongExtra(LibraryAdapter.DATA_ID, LibraryAdapter.INVALID_ID) == LibraryAdapter.HEADER_ID;
		if (!isHeader && FileUtils.canDispatchIntent(intent) && FileUtils.dispatchIntent(mActivity, intent)) return;
		if (isFolder) {
			mActivity.onItemExpanded(intent);
		} else {
			mActivity.onItemClicked(intent);
		}
	}

	public boolean onCreateFancyMenu(Intent intent, View view, float x, float y) {
		String path = intent.getStringExtra(LibraryAdapter.DATA_FILE);
		boolean isParentRow = (path != null && pointsToParentFolder(new File(path)));
		if (!isParentRow) return mActivity.onCreateFancyMenu(intent, view, x, y);
		return true;
	}

	private String getFolderDurationString(File folder) {
		if (folder == null || !folder.isDirectory()) return null;
		String folderPath = folder.getAbsolutePath();
		if (mFolderCache.containsKey(folderPath)) {
			return mFolderCache.get(folderPath);
		}
		long totalDurationMs = 0;
		android.database.Cursor cursor = null;
		try {
			android.net.Uri uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
			String[] projection = { "SUM(" + android.provider.MediaStore.Audio.Media.DURATION + ")" };
			String selection = android.provider.MediaStore.Audio.Media.DATA + " LIKE ?";
			String[] selectionArgs = { folderPath + "/%" };
			cursor = mActivity.getContentResolver().query(uri, projection, selection, selectionArgs, null);
			if (cursor != null && cursor.moveToFirst()) {
				totalDurationMs = cursor.getLong(0);
			}
		} catch (Exception e) {
			android.util.Log.e("VanillaMusic", "Ошибка быстрого запроса времени папки", e);
		} finally {
			if (cursor != null) {
				try { cursor.close(); } catch (Exception e) {}
			}
		}
		String result = null;
		if (totalDurationMs > 0) {
			long seconds = totalDurationMs / 1000;
			long hours = seconds / 3600;
			long minutes = (seconds % 3600) / 60;
			seconds = seconds % 60;
			if (hours > 0) {
				result = String.format("%d:%02d:%02d", hours, minutes, seconds);
			} else {
				result = String.format("%02d:%02d", minutes, seconds);
			}
		}
		mFolderCache.put(folderPath, result);
		return result;
	}
}
