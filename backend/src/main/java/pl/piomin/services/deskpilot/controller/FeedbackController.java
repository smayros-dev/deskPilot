package pl.piomin.services.deskpilot.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.dto.FeedbackResponse;
import pl.piomin.services.deskpilot.model.FeedbackRating;
import pl.piomin.services.deskpilot.service.FeedbackService;

/** The latest rated answers for reviewers and admins (see SecurityConfig), by default the negative ones. */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping
    public List<FeedbackResponse> latest(@RequestParam(defaultValue = "DOWN") FeedbackRating rating) {
        return feedbackService.latest(rating).stream().map(FeedbackResponse::of).toList();
    }
}
