package com.ec.survey.service;

import com.ec.survey.exception.MessageException;
import com.ec.survey.model.Archive;
import com.ec.survey.model.ArchiveFilter;
import com.ec.survey.model.administration.GlobalPrivilege;
import com.ec.survey.model.administration.User;
import com.ec.survey.model.survey.Survey;
import com.ec.survey.tools.Constants;
import com.ec.survey.tools.ConversionTools;
import com.ec.survey.tools.ImportResult;
import com.ec.survey.tools.SurveyExportHelper;
import org.hibernate.query.Query;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

@Service("archiveService")
public class ArchiveService extends BasicService {

	@Resource(name = "taskExecutorLong")
	protected TaskExecutor taskExecutorLong;

	@Autowired
	private SqlQueryService sqlQueryService;

	@Transactional(readOnly = false)
	public void update(Archive archive) {
		Session session = sessionFactory.getCurrentSession();
		archive = (Archive) session.merge(archive);
		session.setReadOnly(archive, false);
		session.update(archive);
		session.flush();
	}

	@Transactional(readOnly = false)
	public void delete(Archive archive) throws IOException {
		String uid = archive.getSurveyUID();

		Session session = sessionFactory.getCurrentSession();
		archive = (Archive) session.merge(archive);
		session.delete(archive);

		// delete archive files
		java.io.File file = fileService.getArchiveFile(uid, uid);
		Files.deleteIfExists(file.toPath());

		file = fileService.getArchiveFile(uid, uid + ".pdf");
		Files.deleteIfExists(file.toPath());

		file = fileService.getArchiveFile(uid, uid + "statistics.pdf");
		Files.deleteIfExists(file.toPath());

		file = fileService.getArchiveFile(uid, uid + "statistics.xls");
		Files.deleteIfExists(file.toPath());

		file = fileService.getArchiveFile(uid, uid + "results.xls");
		Files.deleteIfExists(file.toPath());

		file = fileService.getArchiveFile(uid, uid + "results.zip");
		Files.deleteIfExists(file.toPath());

		file = fileService.getArchiveFile(uid, uid + "results.xls.zip");
		Files.deleteIfExists(file.toPath());

		file = fileService.getArchiveFolder(uid);
		Files.deleteIfExists(file.toPath());

		// delete old archive files
		file = new java.io.File(archiveFileDir + uid);
		Files.deleteIfExists(file.toPath());

		file = new java.io.File(archiveFileDir + uid + ".pdf");
		Files.deleteIfExists(file.toPath());

		file = new java.io.File(archiveFileDir + uid + "statistics.pdf");
		Files.deleteIfExists(file.toPath());

		file = new java.io.File(archiveFileDir + uid + "statistics.xls");
		Files.deleteIfExists(file.toPath());

		file = new java.io.File(archiveFileDir + uid + "results.xls");
		Files.deleteIfExists(file.toPath());

		file = new java.io.File(archiveFileDir + uid + "results.zip");
		Files.deleteIfExists(file.toPath());

		file = new java.io.File(archiveFileDir + uid + "results.xls.zip");
		Files.deleteIfExists(file.toPath());
	}

	@Transactional(readOnly = false)
	public void add(Archive archive) {
		Session session = sessionFactory.getCurrentSession();
		session.saveOrUpdate(archive);
		session.flush();
	}

	@Transactional(readOnly = true)
	public Archive get(int id) {
		Session session = sessionFactory.getCurrentSession();
		return (Archive) session.get(Archive.class, id);
	}

	public Survey restore(Archive archive, User user, String alias) throws Exception {

		java.io.File file = fileService.getArchiveFile(archive.getSurveyUID(), archive.getSurveyUID());
		if (!file.exists())
			file = new java.io.File(archiveFileDir + archive.getSurveyUID());

		if (file.exists()) {
			ImportResult result = SurveyExportHelper.importSurvey(file, fileService, null);

			if (result != null && result.getSurvey() != null) {
				Survey existing = null;
				if (alias != null && alias.length() > 0) {
					existing = surveyService.getSurvey(alias, true, false, false, false, null, true, false, false, false);
				} else {
					existing = surveyService.getSurvey(result.getSurvey().getShortname(), true, false, false, false, null, true, false, false, false);
				}
				
				if (existing != null && existing.getIsDeleted()) {
					// the survey still exists in the database
					surveyService.unmarkAsArchived(existing.getUniqueId());
					delete(archive);
					return surveyService.getSurvey(existing.getId());
				}

				if (existing != null) {
					throw new MessageException("A survey with this alias already exists and cannot be imported: "
							+ result.getSurvey().getShortname());
				} else {

					if (alias != null && alias.length() > 0) {
						result.getSurvey().setShortname(alias);
						if (result.getActiveSurvey() != null) {
							result.getActiveSurvey().setShortname(alias);
						}
						for (Survey s : result.getOldSurveys().values()) {
							s.setShortname(alias);
						}
					}

					int id = surveyService.importSurvey(result, user, true);

					delete(archive);

					return surveyService.getSurvey(id);
				}

			} else {
				throw new MessageException("The survey could not be imported: " + archive.getSurveyTitle());
			}
		}

		throw new MessageException("The survey could not be imported as the file does not exist: " + archive.getSurveyTitle());
	}

	@Transactional(readOnly = true)
	public List<Archive> getAllArchives(ArchiveFilter filter, int page, int rowsPerPage, boolean includingErrors)
			throws Exception {
		Session session = sessionFactory.getCurrentSession();

		String hql = "FROM Archive a WHERE a.error IS NULL";

		if (includingErrors) {
			hql = "FROM Archive a WHERE a.id > 0";
		}

		Map<String, Object> params = new HashMap<>();

		if (filter.getUniqueId() != null && filter.getUniqueId().trim().length() > 0) {
			hql += " AND a.surveyUID LIKE :uid";
			params.put("uid", "%" + filter.getUniqueId() + "%");
		}

		if (filter.getUserId() > 0) {
			hql += " AND a.userId = :userid";
			params.put("userid", filter.getUserId());
		}

		if (filter.getFinished() != null && filter.getFinished()) {
			hql += " AND a.finished = true";
		}

		if (filter.getShortname() != null && filter.getShortname().trim().length() > 0) {
			hql += " AND a.surveyShortname LIKE :shortname";
			params.put(Constants.SHORTNAME, "%" + filter.getShortname() + "%");
		}

		if (filter.getTitle() != null && filter.getTitle().trim().length() > 0) {
			hql += " AND a.surveyTitle LIKE :title";
			params.put("title", "%" + filter.getTitle() + "%");
		}

		if (filter.getOwner() != null && filter.getOwner().trim().length() > 0) {
			hql += " AND a.owner LIKE :owner";
			params.put("owner", "%" + filter.getOwner() + "%");
		}

		if (filter.getCreatedFrom() != null) {
			hql += " AND a.created >= :createdFrom";
			params.put("createdFrom", filter.getCreatedFrom());
		}

		if (filter.getCreatedTo() != null) {
			hql += " AND a.created < :createdTo";

			Calendar c = Calendar.getInstance();
			c.setTime(filter.getCreatedTo());
			c.add(Calendar.DAY_OF_MONTH, 1);
			params.put("createdTo", c.getTime());
		}

		if (filter.getArchivedFrom() != null) {
			hql += " AND a.archived >= :archivedFrom";
			params.put("archivedFrom", filter.getArchivedFrom());
		}

		if (filter.getArchivedTo() != null) {
			hql += " AND a.archived < :archivedTo";

			Calendar c = Calendar.getInstance();
			c.setTime(filter.getArchivedTo());
			c.add(Calendar.DAY_OF_MONTH, 1);
			params.put("archivedTo", c.getTime());
		}

		if (filter.getSortKey() != null && filter.getSortKey().length() > 0) {
			hql += " ORDER BY a." + filter.getSortKey() + " " + filter.getSortOrder();
		}

		Query query = session.createQuery(hql);
		sqlQueryService.setParameters(query, params);

		@SuppressWarnings("unchecked")
		List<Archive> result = query.setFirstResult((page > 1 ? page - 1 : 0) * rowsPerPage).setMaxResults(rowsPerPage).list();
		return result;
	}

	@Transactional(readOnly = true)
	public int getNumberOfArchives(Integer userId) {
		Session session = sessionFactory.getCurrentSession();
		Query query = session.createQuery("SELECT COUNT(*) FROM Archive a WHERE a.userId = :userId")
				.setInteger("userId", userId);

		return ConversionTools.getValue(query.uniqueResult());
	}

	@Transactional(readOnly = false)
	public void markRestoring(Archive archive) {
		archive.setRestoring(true);
		update(archive);
	}

	@Transactional(readOnly = false)
	public void unmarkRestoring(Archive archive) {
		archive.setRestoring(false);
		update(archive);
	}
	
	@Transactional(readOnly = false)
	public boolean archiveSurvey(Survey survey, User u) throws IOException {
		logger.info("start archiving of survey " + survey.getId()); 
		Archive archive = new Archive();
		archive.setArchived(new Date());
		archive.setCreated(survey.getCreated());

		String title = ConversionTools.removeHTML(survey.getTitle(), true).replace("\"", "'");
		if (title.length() > 250)
			title = title.substring(0, 250) + "...";

		archive.setSurveyTitle(title);
		archive.setSurveyUID(survey.getUniqueId());
		archive.setReplies(answerService.getNumberOfAnswerSetsPublished(survey.getShortname(), survey.getUniqueId()));

		archive.setSurveyHasUploadedFiles(survey.getHasUploadElement());

		archive.setSurveyShortname(survey.getShortname());
		archive.setOwner(survey.getOwner().getName());
		archive.setUserId(u.getId());
		StringBuilder langs = new StringBuilder();
		if (survey.getTranslations() != null) {
			for (String s : survey.getTranslations()) {
				langs.append(s);
			}
		}
		archive.setLanguages(langs.toString());
		archiveService.add(archive);
		
		surveyService.markAsArchived(survey.getUniqueId());
		
		return true;
	}
	
	@Transactional
	public Archive getActiveArchive(String shortname) {
		Session session = sessionFactory.getCurrentSession();
		Query query = session.createQuery(
				"FROM Archive a WHERE a.surveyShortname = :shortname and a.finished = false AND a.error IS NULL")
				.setString(Constants.SHORTNAME, shortname);

		@SuppressWarnings("unchecked")
		List<Archive> result = query.list();

		if (!result.isEmpty())
			return result.get(0);

		return null;
	}

	@Transactional
	public Archive getArchive(Integer userid, String shortname) {
		Session session = sessionFactory.getCurrentSession();
		Query query = session.createQuery(
				"FROM Archive a WHERE a.userId = :userId AND a.surveyShortname = :shortname AND a.finished = true AND a.error IS NULL")
				.setInteger("userId", userid).setString(Constants.SHORTNAME, shortname);

		@SuppressWarnings("unchecked")
		List<Archive> result = query.list();

		if (!result.isEmpty())
			return result.get(0);

		return null;
	}

	@Transactional
	public String getSurveyUIDForArchivedSurveyShortname(String shortname) {
		Session session = sessionFactory.getCurrentSession();
		Query query = session.createQuery("SELECT a.surveyUID FROM Archive a WHERE a.surveyShortname = :shortname");
		query.setString(Constants.SHORTNAME, shortname);
		return (String) query.uniqueResult();
	}

	@Transactional
	public List<Archive> getArchivesForUser(int userid) {
		Session session = sessionFactory.getCurrentSession();
		Query query = session.createQuery("FROM Archive a WHERE a.userId = :userId AND a.finished = true AND a.error IS NULL").setInteger("userId", userid);

		@SuppressWarnings("unchecked")
		List<Archive> result = query.list();

		return result;
	}
}
