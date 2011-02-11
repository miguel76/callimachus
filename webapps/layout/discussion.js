// discussion.js

jQuery(function($){
	$("#discussion-tab").each(function() {
		var tab = $(this);
		var onclick = this.getAttribute("onclick");
		if (onclick) {
			var url = location.href.substring(0, location.href.indexOf('?')) + onclick.match(/'(.*)'/)[1];
			jQuery.ajax({ type: 'GET', url: url,
				success: function(doc) { handleDiscussion(tab, url, doc); }
			});
		} else {
			var url = location.href;
			var posts = $(".comment").parent().find(".datetime-locale").map(function(){ return $(this).attr("content"); });
			if (window.localStorage) {
				localStorage.setItem(url, posts.get().join('|'));
			}
		}
	});
	function handleDiscussion(tab, url, data) {
		var posts = $(".comment", data).parent().find(".datetime-locale").map(function(){ return $(this).text(); });
		if (posts.size()) {
			if (window.localStorage && localStorage.getItem(url)) {
				var seen = localStorage.getItem(url).split('|');
				var newer = posts.filter(function(){
					for (var i=0; i<seen.length; i++) {
						if (seen[i] == this)
							return false;
					}
					return true;
				});
				if (newer.size()) {
					tab.text(tab.text() + " (" + newer.size() + ")");
				}
			} else {
				tab.text(tab.text() + " (" + posts.size() + ")");
			}
			tab.css('font-weight', "bold");
		}
	}
});
