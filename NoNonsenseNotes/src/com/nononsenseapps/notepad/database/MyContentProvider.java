package com.nononsenseapps.notepad.database;

import com.nononsenseapps.helpers.UpdateNotifier;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.provider.BaseColumns;

public class MyContentProvider extends ContentProvider {
	public static final String AUTHORITY = "com.nononsenseapps.NotePad";
	public static final String SCHEME = "content://";

	public MyContentProvider() {
	}

	private static final UriMatcher sURIMatcher = new UriMatcher(
			UriMatcher.NO_MATCH);
	static {
		TaskList.addMatcherUris(sURIMatcher);
		Task.addMatcherUris(sURIMatcher);
		Notification.addMatcherUris(sURIMatcher);
	}

	@Override
	public String getType(Uri uri) {
		// TODO add legacy URIs
		switch (sURIMatcher.match(uri)) {
		case Notification.BASEITEMCODE:
		case Notification.BASEURICODE:
		case Notification.WITHTASKQUERYCODE:
		case Notification.WITHTASKQUERYITEMCODE:
			return Notification.CONTENT_TYPE;
		case TaskList.BASEITEMCODE:
		case TaskList.BASEURICODE:
			return TaskList.CONTENT_TYPE;
		case Task.BASEITEMCODE:
		case Task.BASEURICODE:
		case Task.INDENTEDQUERYCODE:
		case Task.SECTIONEDDATEITEMCODE:
		case Task.SECTIONEDDATEQUERYCODE:
		case Task.LEGACYBASEITEMCODE:
		case Task.LEGACYBASEURICODE:
		case Task.LEGACYVISIBLEITEMCODE:
		case Task.LEGACYVISIBLEURICODE:
			return Task.CONTENT_TYPE;
		default:
			throw new IllegalArgumentException("Unknown URI " + uri);
		}
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	synchronized public Uri insert(Uri uri, ContentValues values) {
		final SQLiteDatabase db = DatabaseHandler.getInstance(getContext())
				.getWritableDatabase();

		Uri result = null;

		db.beginTransaction();
		// TODO add legacy URIs
		try {
			final DAO item;
			switch (sURIMatcher.match(uri)) {
			case TaskList.BASEURICODE:
				item = new TaskList(values);
				break;
			case Task.BASEURICODE:
				item = new Task(values);
				break;
			case Notification.BASEURICODE:
			case Notification.WITHTASKQUERYITEMCODE:
				item = new Notification(values);
				break;
			default:
				throw new IllegalArgumentException("Faulty URI provided");
			}

			result = item.insert(getContext(), db);
			db.setTransactionSuccessful();
		}
		finally {
			db.endTransaction();
		}

		return result;
	}

	@Override
	synchronized public int update(Uri uri, ContentValues values,
			String selection, String[] selectionArgs) {
		final SQLiteDatabase db = DatabaseHandler.getInstance(getContext())
				.getWritableDatabase();
		int result = 0;
		final Task t;
		final SQLiteStatement stmt;
		final String sql;
		db.beginTransaction();

		try {
			// TODO add legacy URIs
			switch (sURIMatcher.match(uri)) {
			case TaskList.BASEITEMCODE:
				final TaskList list = new TaskList(uri, values);
				result += db.update(TaskList.TABLE_NAME, list.getContent(),
						TaskList.whereIdIs, list.whereIdArg());
				break;
			case Task.INDENTITEMCODE:
				// indent one

				t = new Task(uri, values);
				if (!t.shouldIndent()) {
					throw new SQLException(
							"Cant indent task without the correct information");
				}

				stmt = db.compileStatement(t.getSQLIndentItem());
				result += stmt.executeUpdateDelete();
				break;
			case Task.UNINDENTITEMCODE:
				// unindent one

				t = new Task(uri, values);
				if (!t.shouldIndent()) {
					throw new SQLException(
							"Cant unindent task without the correct information");
				}

				Task parent = null;
				// Get the parent
				final Cursor c = db
						.query(Task.TABLE_NAME, Task.Columns.FIELDS, String
								.format("%1$s < ? AND %2$s > ? AND %3$s IS ?",
										Task.Columns.LEFT, Task.Columns.RIGHT,
										Task.Columns.DBLIST), new String[] {
								Long.toString(t.left), Long.toString(t.right),
								Long.toString(t.dblist) }, null, null, String
								.format("(%2$s - %1$s) ASC", Task.Columns.LEFT,
										Task.Columns.RIGHT), "1");

				if (c != null && c.getCount() == 1 && c.moveToFirst()) {
					parent = new Task(c);
				}
				c.close();

				if (parent != null) {
					stmt = db.compileStatement(t
							.getSQLUnIndentItem(parent.right));
					result += stmt.executeUpdateDelete();
				}
				break;
			case Task.MOVEITEMLEFTCODE:
				t = new Task(values);
				sql = t.getSQLMoveItemLeft(values);
				if (sql != null) {
					stmt = db.compileStatement(sql);
					result += stmt.executeUpdateDelete();
				}
				break;
			case Task.MOVEITEMRIGHTCODE:
				t = new Task(values);
				sql = t.getSQLMoveItemRight(values);
				if (sql != null) {
					stmt = db.compileStatement(sql);
					result += stmt.executeUpdateDelete();
				}
				break;
//			case Task.MOVESUBTREECODE:
//				// Move subtree
//
//				t = new Task(uri, values);
//				if (!t.shouldMove(values)) {
//					throw new SQLException(
//							"Cant move task without the correct information");
//				}
//
//				stmt = db.compileStatement(t.getSQLMoveSubTree(values));
//				result += stmt.executeUpdateDelete();
//				break;
			case Task.BASEITEMCODE:
				// regular update
				t = new Task(uri, values);
				if (t.getContent().size() > 0) {
					// Something changed in task

					result += db.update(Task.TABLE_NAME, t.getContent(),
							Task.whereIdIs, t.whereIdArg());
				}
				break;
			case Task.BASEURICODE:
				// Batch. No checks made
				result += db.update(Task.TABLE_NAME, values, selection,
						selectionArgs);
				break;
			case Notification.BASEITEMCODE:
			case Notification.WITHTASKQUERYITEMCODE:
				final Notification n = new Notification(uri, values);
				if (n.getContent().size() > 0) {
					result += db.update(Notification.TABLE_NAME,
							n.getContent(), n.whereIdIs, n.whereIdArg());
				}
				break;
			case Notification.BASEURICODE:
				// No checks
				result += db.update(Notification.TABLE_NAME, values, selection,
						selectionArgs);
				break;
			default:
				throw new IllegalArgumentException("Faulty URI provided");
			}

			if (result >= 0) {
				db.setTransactionSuccessful();
			}
		}
		finally {
			db.endTransaction();
		}

		if (result >= 0) {
			DAO.notifyProviderOnChange(getContext(), uri);
		}

		return result;
	}

	synchronized private int safeDeleteItem(final SQLiteDatabase db,
			final String tableName, final Uri uri) {
		db.beginTransaction();
		int result = 0;
		try {
			result += db.delete(tableName, BaseColumns._ID + " IS ?",
					new String[] { uri.getLastPathSegment() });
			db.setTransactionSuccessful();
		}
		finally {
			db.endTransaction();
		}

		return result;
	}

	@Override
	synchronized public int delete(Uri uri, String selection,
			String[] selectionArgs) {
		final SQLiteDatabase db = DatabaseHandler.getInstance(getContext())
				.getWritableDatabase();
		int result = 0;
		// TODO add legacy URIs
		switch (sURIMatcher.match(uri)) {
		case TaskList.BASEITEMCODE:
			result += safeDeleteItem(db, TaskList.TABLE_NAME, uri);
			break;
		case Task.BASEITEMCODE:
			result += safeDeleteItem(db, Task.TABLE_NAME, uri);
			break;
		case Notification.BASEITEMCODE:
		case Notification.WITHTASKQUERYITEMCODE:
			result += safeDeleteItem(db, Notification.TABLE_NAME, uri);
			break;
		default:
			throw new IllegalArgumentException("Faulty URI provided");
		}

		if (result > 0) {
			DAO.notifyProviderOnChange(getContext(), uri);
		}
		return result;
	}

	@Override
	synchronized public Cursor query(Uri uri, String[] projection,
			String selection, String[] selectionArgs, String sortOrder) {
		Cursor result = null;
		final long id;
		// TODO add legacy URIs
		switch (sURIMatcher.match(uri)) {
		case TaskList.BASEURICODE:
			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(TaskList.TABLE_NAME, projection, selection,
							selectionArgs, null, null, sortOrder);
			result.setNotificationUri(getContext().getContentResolver(),
					TaskList.URI);
			break;
		case TaskList.BASEITEMCODE:
			id = Long.parseLong(uri.getLastPathSegment());
			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(TaskList.TABLE_NAME, projection,
							TaskList.Columns._ID + " IS ?",
							new String[] { String.valueOf(id) }, null, null,
							sortOrder);
			result.setNotificationUri(getContext().getContentResolver(), uri);
			break;
		case Task.INDENTEDQUERYCODE:
			// Ignore selection param
			// Selection arg must be the list id
			// Sort order is left ASC, no exceptions
			if (selectionArgs == null || selectionArgs.length != 1) {
				throw new SQLException(
						"Indented URI requires only argument to be the list id!");
			}

			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.rawQuery(Task.getSQLIndentedQuery(projection),
							new String[] { selectionArgs[0], selectionArgs[0] });
			result.setNotificationUri(getContext().getContentResolver(),
					Task.URI);
			break;
		case Task.DELETEDQUERYCODE:
			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(Task.DELETE_TABLE_NAME, projection, selection,
							selectionArgs, null, null, sortOrder);

			result.setNotificationUri(getContext().getContentResolver(),
					Task.URI_DELETED_QUERY);
			break;
		case Task.DELETEDITEMCODE:
			id = Long.parseLong(uri.getLastPathSegment());
			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(Task.DELETE_TABLE_NAME, projection,
							Task.Columns._ID + " IS ?",
							new String[] { String.valueOf(id) }, null, null,
							null);
			result.setNotificationUri(getContext().getContentResolver(), uri);
			break;
		case Task.BASEURICODE:
			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(Task.TABLE_NAME, projection, selection,
							selectionArgs, null, null, sortOrder);

			result.setNotificationUri(getContext().getContentResolver(),
					Task.URI);
			break;
		case Task.BASEITEMCODE:
			id = Long.parseLong(uri.getLastPathSegment());
			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(Task.TABLE_NAME, projection,
							Task.Columns._ID + " IS ?",
							new String[] { String.valueOf(id) }, null, null,
							sortOrder);
			result.setNotificationUri(getContext().getContentResolver(), uri);
			break;
		case Task.SECTIONEDDATEQUERYCODE:
			// Add list null because that's what the headers will have
			if (selectionArgs == null || selectionArgs.length == 0) {
				throw new SQLException(
						"Need a listid as first arg at the moment for this view!");
			}
			// Create view if not exists
			DatabaseHandler.getInstance(getContext()).getWritableDatabase()
					.execSQL(Task.CREATE_SECTIONED_DATE_VIEW(selectionArgs[0]));

			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(Task.getSECTION_DATE_VIEW_NAME(selectionArgs[0]),
							projection,
							selection,
							selectionArgs,
							null,
							null,
							Task.SECRET_TYPEID + "," + Task.Columns.DUE + ","
									+ Task.SECRET_TYPEID2);

			result.setNotificationUri(getContext().getContentResolver(),
					Task.URI);
			break;
		case Task.HISTORYQUERYCODE:
			result = DatabaseHandler
			.getInstance(getContext())
			.getReadableDatabase()
			.query(Task.HISTORY_TABLE_NAME,
					projection,
					selection,
					selectionArgs,
					null,
					null,
					Task.Columns.UPDATED + " ASC");
			// SQLite timestamp in updated column.

			result.setNotificationUri(getContext().getContentResolver(), uri);
			break;
		case Notification.BASEITEMCODE:
			id = Long.parseLong(uri.getLastPathSegment());
			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(Notification.TABLE_NAME, projection,
							Notification.Columns._ID + " IS ?",
							new String[] { String.valueOf(id) }, null, null,
							sortOrder);
			result.setNotificationUri(getContext().getContentResolver(), uri);
			break;
		case Notification.WITHTASKQUERYITEMCODE:
			id = Long.parseLong(uri.getLastPathSegment());
			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(Notification.WITH_TASK_VIEW_NAME, projection,
							Notification.Columns._ID + " IS ?",
							new String[] { String.valueOf(id) }, null, null,
							sortOrder);
			result.setNotificationUri(getContext().getContentResolver(), uri);
			break;
		case Notification.BASEURICODE:
			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(Notification.TABLE_NAME, projection, selection,
							selectionArgs, null, null, sortOrder);
			result.setNotificationUri(getContext().getContentResolver(), uri);
			break;
		case Notification.WITHTASKQUERYCODE:
			result = DatabaseHandler
					.getInstance(getContext())
					.getReadableDatabase()
					.query(Notification.WITH_TASK_VIEW_NAME, projection,
							selection, selectionArgs, null, null, sortOrder);
			result.setNotificationUri(getContext().getContentResolver(), uri);
			break;
		case Task.LEGACYBASEURICODE:
		case Task.LEGACYVISIBLEURICODE:
		default:
			throw new IllegalArgumentException("Faulty URI provided");
		}

		return result;
	}

}