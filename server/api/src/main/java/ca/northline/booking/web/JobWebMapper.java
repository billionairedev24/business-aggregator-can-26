package ca.northline.booking.web;

import ca.northline.booking.application.JobViews.Customer;
import ca.northline.booking.application.JobViews.JobDetail;
import ca.northline.booking.application.JobViews.JobSummary;
import ca.northline.booking.application.JobViews.TimelineEntry;
import ca.northline.booking.application.MediaCatalog.MediaInfo;
import ca.northline.booking.domain.Approval;
import ca.northline.booking.web.JobResponses.ApprovalResponse;
import ca.northline.booking.web.JobResponses.CustomerResponse;
import ca.northline.booking.web.JobResponses.JobDetailResponse;
import ca.northline.booking.web.JobResponses.JobResponse;
import ca.northline.booking.web.JobResponses.MediaResponse;
import ca.northline.booking.web.JobResponses.TimelineResponse;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper
interface JobWebMapper {

    JobResponse toResponse(JobSummary job);

    List<JobResponse> toResponses(List<JobSummary> jobs);

    JobDetailResponse toResponse(JobDetail job);

    CustomerResponse toResponse(Customer customer);

    TimelineResponse toResponse(TimelineEntry entry);

    ApprovalResponse toResponse(Approval approval);

    MediaResponse toResponse(MediaInfo media);
}
